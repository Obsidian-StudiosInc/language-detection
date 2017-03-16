package com.cybozu.labs.langdetect;

import java.io.IOException;
import java.io.Reader;
import java.lang.Character.UnicodeBlock;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import com.cybozu.labs.langdetect.util.NGram;

/**
 * {@link Detector} class is to detect language from specified text. Its
 * instance is able to be constructed via the factory class
 * {@link DetectorFactory}.
 * <p>
 * After appending a target text to the {@link Detector} instance with
 * {@link #append(Reader)} or {@link #append(String)}, the detector provides the
 * language detection results for target text via {@link #detect()} or
 * {@link #getProbabilities()}. {@link #detect()} method returns a single
 * language name which has the highest probability. {@link #getProbabilities()}
 * methods returns a list of multiple languages and their probabilities.
 * <p>
 * The detector has some parameters for language detection. See
 * {@link #setAlpha(double)}, {@link #setMaxTextLength(int)} and
 * {@link #setPriorMap(Map)}.
 *
 * <pre>
 * import java.util.ArrayList;
 * import com.cybozu.labs.langdetect.Detector;
 * import com.cybozu.labs.langdetect.DetectorFactory;
 * import com.cybozu.labs.langdetect.Language;
 *
 * class LangDetectSample
 * {
 *   public void init (String profileDirectory) throws LangDetectException
 *   {
 *     DetectorFactory.loadProfile (profileDirectory);
 *   }
 *
 *   public String detect (String text) throws LangDetectException
 *   {
 *     Detector detector = DetectorFactory.create ();
 *     detector.append (text);
 *     return detector.detect ();
 *   }
 *
 *   public List <Language> detectLangs (String text) throws LangDetectException
 *   {
 *     Detector detector = DetectorFactory.create ();
 *     detector.append (text);
 *     return detector.getProbabilities ();
 *   }
 * }
 * </pre>
 * <ul>
 * <li>4x faster improvement based on Elmer Garduno's code. Thanks!</li>
 * </ul>
 *
 * @author Nakatani Shuyo
 * @see DetectorFactory
 */
public class Detector
{
  private static final double ALPHA_DEFAULT = 0.5;
  private static final double ALPHA_WIDTH = 0.05;

  private static final int ITERATION_LIMIT = 1000;
  private static final double PROB_THRESHOLD = 0.1;
  private static final double CONV_THRESHOLD = 0.99999;
  private static final int BASE_FREQ = 10000;
  private static final String UNKNOWN_LANG = "unknown";

  private static final Pattern URL_REGEX = Pattern.compile ("https?://[-_.?&~;+=/#0-9A-Za-z]{1,2076}");
  private static final Pattern MAIL_REGEX = Pattern.compile ("[-_.0-9A-Za-z]{1,64}@[-_0-9A-Za-z]{1,255}[-_.0-9A-Za-z]{1,255}");

  private final Map <String, double []> wordLangProbMap;
  private final List <String> langlist;

  private StringBuffer text;
  private double [] langprob = null;

  private double m_dAlpha = ALPHA_DEFAULT;
  private int m_nN_trial = 7;
  private int max_text_length = 10000;
  private double [] priorMap = null;
  private boolean verbose = false;
  private Long seed = null;

  /**
   * Constructor. Detector instance can be constructed via
   * {@link DetectorFactory#create()}.
   *
   * @param factory
   *        {@link DetectorFactory} instance (only DetectorFactory inside)
   */
  public Detector (final DetectorFactory factory)
  {
    this.wordLangProbMap = factory.wordLangProbMap;
    this.langlist = factory.langlist;
    this.text = new StringBuffer ();
    this.seed = factory.seed;
  }

  /**
   * Set Verbose Mode(use for debug).
   */
  public void setVerbose ()
  {
    this.verbose = true;
  }

  /**
   * Set smoothing parameter. The default value is 0.5(i.e. Expected Likelihood
   * Estimate).
   *
   * @param alpha
   *        the smoothing parameter
   */
  public void setAlpha (final double alpha)
  {
    this.m_dAlpha = alpha;
  }

  /**
   * Set number of trials variable
   *
   * @param n_trial
   *        number of trials
   */
  public void setTrials (final int n_trial)
  {
    this.m_nN_trial = n_trial;
  }

  /**
   * Set prior information about language probabilities.
   *
   * @param priorMap
   *        the priorMap to set
   * @throws LangDetectException
   *         in case of negative priority
   */
  public void setPriorMap (final Map <String, Double> priorMap) throws LangDetectException
  {
    this.priorMap = new double [langlist.size ()];
    double sump = 0;
    for (int i = 0; i < this.priorMap.length; ++i)
    {
      final String lang = langlist.get (i);
      if (priorMap.containsKey (lang))
      {
        final double p = priorMap.get (lang);
        if (p < 0)
          throw new LangDetectException (ELangDetectErrorCode.InitParamError,
                                         "Prior probability must be non-negative.");
        this.priorMap[i] = p;
        sump += p;
      }
    }
    if (sump <= 0)
      throw new LangDetectException (ELangDetectErrorCode.InitParamError,
                                     "More one of prior probability must be non-zero.");
    for (int i = 0; i < this.priorMap.length; ++i)
      this.priorMap[i] /= sump;
  }

  /**
   * Specify max size of target text to use for language detection. The default
   * value is 10000(10KB).
   *
   * @param max_text_length
   *        the max_text_length to set
   */
  public void setMaxTextLength (final int max_text_length)
  {
    this.max_text_length = max_text_length;
  }

  /**
   * Append the target text for language detection. This method read the text
   * from specified input reader. If the total size of target text exceeds the
   * limit size specified by {@link Detector#setMaxTextLength(int)}, the rest is
   * cut down.
   *
   * @param reader
   *        the input reader (BufferedReader as usual)
   * @throws IOException
   *         Can't read the reader.
   */
  public void append (final Reader reader) throws IOException
  {
    final char [] buf = new char [max_text_length / 2];
    while (text.length () < max_text_length && reader.ready ())
    {
      final int length = reader.read (buf);
      append (new String (buf, 0, length));
    }
  }

  /**
   * Append the target text for language detection. If the total size of target
   * text exceeds the limit size specified by
   * {@link Detector#setMaxTextLength(int)}, the rest is cut down.
   *
   * @param text
   *        the target text to append
   */
  public void append (String text)
  {
    text = URL_REGEX.matcher (text).replaceAll (" ");
    text = MAIL_REGEX.matcher (text).replaceAll (" ");
    text = NGram.normalize_vi (text);
    char pre = 0;
    for (int i = 0; i < text.length () && i < max_text_length; ++i)
    {
      final char c = text.charAt (i);
      if (c != ' ' || pre != ' ')
        this.text.append (c);
      pre = c;
    }
  }

  /**
   * Cleaning text to detect (eliminate URL, e-mail address and Latin sentence
   * if it is not written in Latin alphabet)
   */
  private void _cleaningText ()
  {
    int latinCount = 0, nonLatinCount = 0;
    for (int i = 0; i < text.length (); ++i)
    {
      final char c = text.charAt (i);
      if (c <= 'z' && c >= 'A')
      {
        ++latinCount;
      }
      else
        if (c >= '\u0300' && UnicodeBlock.of (c) != UnicodeBlock.LATIN_EXTENDED_ADDITIONAL)
        {
          ++nonLatinCount;
        }
    }
    if (latinCount * 2 < nonLatinCount)
    {
      final StringBuffer textWithoutLatin = new StringBuffer ();
      for (int i = 0; i < text.length (); ++i)
      {
        final char c = text.charAt (i);
        if (c > 'z' || c < 'A')
          textWithoutLatin.append (c);
      }
      text = textWithoutLatin;
    }

  }

  /**
   * Detect language of the target text and return the language name which has
   * the highest probability.
   *
   * @return detected language name which has most probability.
   * @throws LangDetectException
   *         code = ErrorCode.CantDetectError : Can't detect because of no valid
   *         features in text
   */
  public String detect () throws LangDetectException
  {
    final List <Language> probabilities = getProbabilities ();
    if (probabilities.size () > 0)
      return probabilities.get (0).getLanguage ();
    return UNKNOWN_LANG;
  }

  /**
   * Get language candidates which have high probabilities
   *
   * @return possible languages list (whose probabilities are over
   *         PROB_THRESHOLD, ordered by probabilities descendently
   * @throws LangDetectException
   *         code = ErrorCode.CantDetectError : Can't detect because of no valid
   *         features in text
   */
  public List <Language> getProbabilities () throws LangDetectException
  {
    if (langprob == null)
      _detectBlock ();

    final List <Language> list = _sortProbability (langprob);
    return list;
  }

  /**
   * @throws LangDetectException
   */
  private void _detectBlock () throws LangDetectException
  {
    _cleaningText ();
    final List <String> ngrams = _extractNGrams ();
    if (ngrams.size () == 0)
      throw new LangDetectException (ELangDetectErrorCode.CantDetectError, "no features in text");

    langprob = new double [langlist.size ()];

    final Random rand = new Random ();
    if (seed != null)
      rand.setSeed (seed);
    for (int t = 0; t < m_nN_trial; ++t)
    {
      final double [] prob = _initProbability ();
      final double alpha = this.m_dAlpha + rand.nextGaussian () * ALPHA_WIDTH;

      for (int i = 0;; ++i)
      {
        final int r = rand.nextInt (ngrams.size ());
        _updateLangProb (prob, ngrams.get (r), alpha);
        if (i % 5 == 0)
        {
          if (_normalizeProb (prob) > CONV_THRESHOLD || i >= ITERATION_LIMIT)
            break;
          if (verbose)
            System.out.println ("> " + _sortProbability (prob));
        }
      }
      for (int j = 0; j < langprob.length; ++j)
        langprob[j] += prob[j] / m_nN_trial;
      if (verbose)
        System.out.println ("==> " + _sortProbability (prob));
    }
  }

  /**
   * Initialize the map of language probabilities. If there is the specified
   * prior map, use it as initial map.
   *
   * @return initialized map of language probabilities
   */
  private double [] _initProbability ()
  {
    final double [] prob = new double [langlist.size ()];
    if (priorMap != null)
    {
      for (int i = 0; i < prob.length; ++i)
        prob[i] = priorMap[i];
    }
    else
    {
      for (int i = 0; i < prob.length; ++i)
        prob[i] = 1.0 / langlist.size ();
    }
    return prob;
  }

  /**
   * Extract n-grams from target text
   *
   * @return n-grams list
   */
  private List <String> _extractNGrams ()
  {
    final List <String> list = new ArrayList<> ();
    final NGram ngram = new NGram ();
    for (int i = 0; i < text.length (); ++i)
    {
      ngram.addChar (text.charAt (i));
      for (int n = 1; n <= NGram.N_GRAM; ++n)
      {
        final String w = ngram.get (n);
        if (w != null && wordLangProbMap.containsKey (w))
          list.add (w);
      }
    }
    return list;
  }

  /**
   * update language probabilities with N-gram string(N=1,2,3)
   *
   * @param word
   *        N-gram string
   */
  private boolean _updateLangProb (final double [] prob, final String word, final double alpha)
  {
    if (word == null || !wordLangProbMap.containsKey (word))
      return false;

    final double [] langProbMap = wordLangProbMap.get (word);
    if (verbose)
      System.out.println (word + "(" + _unicodeEncode (word) + "):" + _wordProbToString (langProbMap));

    final double weight = alpha / BASE_FREQ;
    for (int i = 0; i < prob.length; ++i)
    {
      prob[i] *= weight + langProbMap[i];
    }
    return true;
  }

  private String _wordProbToString (final double [] prob)
  {
    try (final Formatter formatter = new Formatter ())
    {
      for (int j = 0; j < prob.length; ++j)
      {
        final double p = prob[j];
        if (p >= 0.00001)
          formatter.format (" %s:%.5f", langlist.get (j), p);
      }
      return formatter.toString ();
    }
  }

  /**
   * normalize probabilities and check convergence by the maximum probability
   *
   * @return maximum of probabilities
   */
  static private double _normalizeProb (final double [] prob)
  {
    double maxp = 0, sump = 0;
    for (final double aElement : prob)
      sump += aElement;
    for (int i = 0; i < prob.length; ++i)
    {
      final double p = prob[i] / sump;
      if (maxp < p)
        maxp = p;
      prob[i] = p;
    }
    return maxp;
  }

  /**
   * @param prob
   *        HashMap
   * @return lanugage candidates order by probabilities descendently
   */
  private List <Language> _sortProbability (final double [] prob)
  {
    final List <Language> list = new ArrayList<> ();
    for (int j = 0; j < prob.length; ++j)
    {
      final double p = prob[j];
      if (p > PROB_THRESHOLD)
      {
        for (int i = 0; i <= list.size (); ++i)
        {
          if (i == list.size () || list.get (i).getProbability () < p)
          {
            list.add (i, new Language (langlist.get (j), p));
            break;
          }
        }
      }
    }
    return list;
  }

  /**
   * unicode encoding (for verbose mode)
   *
   * @param word
   * @return
   */
  private static String _unicodeEncode (final String word)
  {
    final StringBuilder buf = new StringBuilder (word.length ());
    for (final char ch : word.toCharArray ())
    {
      if (ch >= '\u0080')
      {
        String st = Integer.toHexString (0x10000 + ch);
        while (st.length () < 4)
          st = "0" + st;
        buf.append ("\\u").append (st.subSequence (1, 5));
      }
      else
      {
        buf.append (ch);
      }
    }
    return buf.toString ();
  }

}

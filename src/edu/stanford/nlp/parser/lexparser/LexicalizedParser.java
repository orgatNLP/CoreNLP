// Stanford Parser -- a probabilistic lexicalized NL CFG parser
// Copyright (c) 2002 - 2011 The Board of Trustees of
// The Leland Stanford Junior University. All Rights Reserved.
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
// For more information, bug reports, fixes, contact:
//    Christopher Manning
//    Dept of Computer Science, Gates 1A
//    Stanford CA 94305-9010
//    USA
//    parser-support@lists.stanford.edu
//    http://nlp.stanford.edu/software/lex-parser.shtml

package edu.stanford.nlp.parser.lexparser;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.io.NumberRangesFileFilter;
import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.process.TokenizerFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.Tokenizer;
import edu.stanford.nlp.util.ErasureUtils;
import edu.stanford.nlp.util.Function;
import edu.stanford.nlp.util.HashIndex;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.tagger.io.TaggedFileRecord;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.ReflectionLoading;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.Timing;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.util.concurrent.MulticoreWrapper;
import edu.stanford.nlp.util.concurrent.ThreadsafeProcessor;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * This class provides the top-level API and command-line interface to a set
 * of reasonably good treebank-trained parsers.  The name reflects the main
 * factored parsing classify, which provides a lexicalized PCFG parser
 * implemented as a product
 * classify of a plain PCFG parser and a lexicalized dependency parser.
 * But you can also run either component parser alone.  In particular, it
 * is often useful to do unlexicalized PCFG parsing by using just that
 * component parser.
 * <p>
 * See the package documentation for more details and examples of use.
 * <p>
 * For information on invoking the parser from the command-line, and for
 * a more detailed list of options, see the {@link #main} method.
 * <p>
 * Note that training on a 1 million word treebank requires a fair amount of
 * memory to run.  Try -mx1500m to increase the memory allocated by the JVM.
 *
 * @author Dan Klein (original version)
 * @author Christopher Manning (better features, ParserParams, serialization)
 * @author Roger Levy (internationalization)
 * @author Teg Grenager (grammar compaction, tokenization, etc.)
 * @author Galen Andrew (considerable refactoring)
 * @author John Bauer (made threadsafe)
 */
public class LexicalizedParser implements Function<List<? extends HasWord>, Tree>, ParserQueryFactory, Serializable {

  public Lexicon lex;
  public BinaryGrammar bg;
  public UnaryGrammar ug;
  public DependencyGrammar dg;
  public Index<String> stateIndex, wordIndex, tagIndex;

  private Options op;
  public Options getOp() { return op; }

  public Reranker reranker = null;

  public TreebankLangParserParams getTLPParams() { return op.tlpParams; }

  public TreebankLanguagePack treebankLanguagePack() { return getTLPParams().treebankLanguagePack(); }

  private static final String SERIALIZED_PARSER_PROPERTY = "edu.stanford.nlp.SerializedLexicalizedParser";
  public static final String DEFAULT_PARSER_LOC = ((System.getenv("NLP_PARSER") != null) ?
                                                   System.getenv("NLP_PARSER") :
                                                   "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz");

  /**
   * Construct a new LexicalizedParser object from a previously
   * serialized grammar read from a System property
   * <code>edu.stanford.nlp.SerializedLexicalizedParser</code>, or a
   * default classpath location
   * ({@code edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz}).
   */
  public static LexicalizedParser loadModel() {
    return loadModel(new Options());
  }

  /**
   * Construct a new LexicalizedParser object from a previously
   * serialized grammar read from a System property
   * <code>edu.stanford.nlp.SerializedLexicalizedParser</code>, or a
   * default classpath location
   * ({@code edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz}).
   *
   * @param op Options to the parser.  These get overwritten by the
   *           Options read from the serialized parser; I think the only
   *           thing determined by them is the encoding of the grammar
   *           iff it is a text grammar
   */
  public static LexicalizedParser loadModel(Options op,
                                            String ... extraFlags) {
    String source = System.getProperty(SERIALIZED_PARSER_PROPERTY);
    if (source == null) {
      source = DEFAULT_PARSER_LOC;
    }
    return loadModel(source, op, extraFlags);
  }

  public static LexicalizedParser loadModel(String parserFileOrUrl,
                                            String ... extraFlags) {
    return loadModel(parserFileOrUrl, new Options(), extraFlags);
  }

  /**
   * Construct a new LexicalizedParser.  This loads a grammar
   * that was previously assembled and stored as a serialized file.
   * @param parserFileOrUrl Filename/URL to load parser from
   * @param op Options for this parser. These will normally be overwritten
   *     by options stored in the file
   * @throws IllegalArgumentException If parser data cannot be loaded
   */
  public static LexicalizedParser loadModel(String parserFileOrUrl, Options op,
                                            String ... extraFlags) {
    //    System.err.print("Loading parser from file " + parserFileOrUrl);
    LexicalizedParser parser = getParserFromFile(parserFileOrUrl, op);
    if (extraFlags.length > 0) {
      parser.setOptionFlags(extraFlags);
    }
    return parser;
  }

  /**
   * Reads one object from the given ObjectInputStream, which is
   * assumed to be a LexicalizedParser.  Throws a ClassCastException
   * if this is not true.  The stream is not closed.
   */
  public static LexicalizedParser loadModel(ObjectInputStream ois) {
    try {
      Object o = ois.readObject();
      if (o instanceof LexicalizedParser) {
        return (LexicalizedParser) o;
      }
      throw new ClassCastException("Wanted LexicalizedParser, got " +
                                   o.getClass());
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static LexicalizedParser loadModelFromZip(String zipFilename,
                                                   String modelName) {
    LexicalizedParser parser = null;
    try {
      File file = new File(zipFilename);
      if (file.exists()) {
        ZipFile zin = new ZipFile(file);
        ZipEntry zentry = zin.getEntry(modelName);
        if (zentry != null) {
          InputStream in = zin.getInputStream(zentry);
          // gunzip it if necessary
          if (modelName.endsWith(".gz")) {
            in = new GZIPInputStream(in);
          }
          ObjectInputStream ois = new ObjectInputStream(in);
          parser = loadModel(ois);
          ois.close();
          in.close();
        }
        zin.close();
      } else {
        throw new FileNotFoundException("Could not find " + modelName +
                                        " inside " + zipFilename);
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
    return parser;
  }


  public LexicalizedParser(Lexicon lex, BinaryGrammar bg, UnaryGrammar ug, DependencyGrammar dg, Index<String> stateIndex, Index<String> wordIndex, Index<String> tagIndex, Options op) {
    this.lex = lex;
    this.bg = bg;
    this.ug = ug;
    this.dg = dg;
    this.stateIndex = stateIndex;
    this.wordIndex = wordIndex;
    this.tagIndex = tagIndex;
    this.op = op;
  }


  /**
   * Construct a new LexicalizedParser.
   *
   * @param trainTreebank a treebank to train from
   */
  public static LexicalizedParser trainFromTreebank(Treebank trainTreebank,
                                                    GrammarCompactor compactor,
                                                    Options op) {
    return getParserFromTreebank(trainTreebank, null, 1.0, compactor, op, null, null);
  }

  public static LexicalizedParser trainFromTreebank(String treebankPath,
                                                    FileFilter filt,
                                                    Options op) {
    return trainFromTreebank(makeTreebank(treebankPath, op, filt), op);
  }

  public static LexicalizedParser trainFromTreebank(Treebank trainTreebank,
                                                    Options op) {
    return trainFromTreebank(trainTreebank, null, op);
  }


  /**
   * Converts a Sentence/List/String into a Tree.  If it can't be parsed,
   * it is made into a trivial tree in which each word is attached to a
   * dummy tag ("X") and then to a start nonterminal (also "X").  In all
   * circumstances, the input will be treated as a single sentence to be
   * parsed.
   *
   * @param in The input Sentence/List/String
   * @return A Tree that is the parse tree for the sentence.  If the parser
   *         fails, a new Tree is synthesized which attaches all words to the
   *         root.
   * @throws IllegalArgumentException If argument isn't a List or String
   */
  @Override
  public Tree apply(List<? extends HasWord> lst) {
    return parse(lst);
  }

  /**
   * Will parse the text in <code>sentence</code> as if it represented
   * a single sentence by first processing it with a tokenizer.
   */
  public Tree parse(String sentence) {
    TokenizerFactory<? extends HasWord> tf = op.tlpParams.treebankLanguagePack().getTokenizerFactory();
    Tokenizer<? extends HasWord> tokenizer = tf.getTokenizer(new BufferedReader(new StringReader(sentence)));
    return parse(tokenizer.tokenize());
  }

  /**
   * Will process a list of strings into a list of HasWord and return
   * the parse tree associated with that list.
   */
  public Tree parseStrings(List<String> lst) {
    List<Word> words = new ArrayList<Word>();
    for (String word : lst) {
      words.add(new Word(word));
    }
    return parse(words);
  }

  /**
   * Parses the list of HasWord.  If the parse fails for some reason,
   * an X tree is returned instead of barfing.
   */
  public Tree parse(List<? extends HasWord> lst) {
    try {
      ParserQuery pq = parserQuery();
      if (pq.parse(lst)) {
        Tree bestparse = pq.getBestParse();
        // -10000 denotes unknown words
        bestparse.setScore(pq.getPCFGScore() % -10000.0);
        return bestparse;
      }
    } catch (Exception e) {
      System.err.println("Following exception caught during parsing:");
      e.printStackTrace();
      System.err.println("Recovering using fall through strategy: will construct an (X ...) tree.");
    }
    // if can't parse or exception, fall through
    // TODO: merge with ParserAnnotatorUtils
    TreeFactory lstf = new LabeledScoredTreeFactory();
    List<Tree> lst2 = new ArrayList<Tree>();
    for (HasWord obj : lst) {
      String s = obj.word();
      Tree t = lstf.newLeaf(s);
      Tree t2 = lstf.newTreeNode("X", Collections.singletonList(t));
      lst2.add(t2);
    }
    return lstf.newTreeNode("X", lst2);
  }

  public List<Tree> parseMultiple(final List<? extends List<? extends HasWord>> sentences) {
    List<Tree> trees = new ArrayList<Tree>();
    for (List<? extends HasWord> sentence : sentences) {
      trees.add(parse(sentence));
    }
    return trees;
  }

  /**
   * Will launch multiple threads which calls <code>parse</code> on
   * each of the <code>sentences</code> in order, returning the
   * resulting parse trees in the same order.
   */ 
  public List<Tree> parseMultiple(final List<? extends List<? extends HasWord>> sentences, final int nthreads) {
    MulticoreWrapper<List<? extends HasWord>, Tree> wrapper = new MulticoreWrapper<List<? extends HasWord>, Tree>(nthreads, new ThreadsafeProcessor<List<? extends HasWord>, Tree>() {
        public Tree process(List<? extends HasWord> sentence) {
          return parse(sentence);
        }
        public ThreadsafeProcessor<List<? extends HasWord>, Tree> newInstance() {
          return this;
        }
      });
    List<Tree> trees = new ArrayList<Tree>();
    for (List<? extends HasWord> sentence : sentences) {
      wrapper.put(sentence);
      while (wrapper.peek()) {
        trees.add(wrapper.poll());
      }
    }
    wrapper.join();
    while (wrapper.peek()) {
      trees.add(wrapper.poll());
    }
    return trees;
  }

  /** Return a TreePrint for formatting parsed output trees.
   *  @return A TreePrint for formatting parsed output trees.
   */
  public TreePrint getTreePrint() {
    return op.testOptions.treePrint(op.tlpParams);
  }

  /**
   * Similar to parse(), but instead of returning an X tree on failure, returns null.
   */
  public Tree parseTree(List<? extends HasWord> sentence) {
    ParserQuery pq = parserQuery();
    if (pq.parse(sentence)) {
      return pq.getBestParse();
    } else {
      return null;
    }
  }

  public ParserQuery parserQuery() {
    if (reranker == null) {
      return new LexicalizedParserQuery(this);
    } else {
      return new RerankingParserQuery(op, new LexicalizedParserQuery(this), reranker);
    }
  }

  public LexicalizedParserQuery lexicalizedParserQuery() {
    return new LexicalizedParserQuery(this);
  }  

  public static LexicalizedParser getParserFromFile(String parserFileOrUrl, Options op) {
    LexicalizedParser pd = getParserFromSerializedFile(parserFileOrUrl);
    if (pd == null) {
      pd = getParserFromTextFile(parserFileOrUrl, op);
    }
    return pd;
  }

  private static Treebank makeTreebank(String treebankPath, Options op, FileFilter filt) {
    System.err.println("Training a parser from treebank dir: " + treebankPath);
    Treebank trainTreebank = op.tlpParams.diskTreebank();
    System.err.print("Reading trees...");
    if (filt == null) {
      trainTreebank.loadPath(treebankPath);
    } else {
      trainTreebank.loadPath(treebankPath, filt);
    }

    Timing.tick("done [read " + trainTreebank.size() + " trees].");
    return trainTreebank;
  }

  private static DiskTreebank makeSecondaryTreebank(String treebankPath, Options op, FileFilter filt) {
    System.err.println("Additionally training using secondary disk treebank: " + treebankPath + ' ' + filt);
    DiskTreebank trainTreebank = op.tlpParams.diskTreebank();
    System.err.print("Reading trees...");
    if (filt == null) {
      trainTreebank.loadPath(treebankPath);
    } else {
      trainTreebank.loadPath(treebankPath, filt);
    }
    Timing.tick("done [read " + trainTreebank.size() + " trees].");
    return trainTreebank;
  }

  public Lexicon getLexicon() {
    return lex;
  }

  /**
   * Saves the parser defined by pd to the given filename.
   * If there is an error, a RuntimeIOException is thrown.
   */
  public void saveParserToSerialized(String filename) {
    try {
      System.err.print("Writing parser in serialized format to file " + filename + ' ');
      ObjectOutputStream out = IOUtils.writeStreamFromString(filename);
      out.writeObject(this);
      out.close();
      System.err.println("done.");
    } catch (IOException ioe) {
      throw new RuntimeIOException(ioe);
    }
  }

  /**
   * Saves the parser defined by pd to the given filename.
   * If there is an error, a RuntimeIOException is thrown.
   */
  public void saveParserToTextFile(String filename) {
    if (reranker != null) {
      throw new UnsupportedOperationException("Sorry, but parsers with rerankers cannot be saved to text file");
    }
    try {
      System.err.print("Writing parser in text grammar format to file " + filename);
      OutputStream os;
      if (filename.endsWith(".gz")) {
        // it's faster to do the buffering _outside_ the gzipping as here
        os = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(filename)));
      } else {
        os = new BufferedOutputStream(new FileOutputStream(filename));
      }
      PrintWriter out = new PrintWriter(os);
      String prefix = "BEGIN ";

      out.println(prefix + "OPTIONS");
      op.writeData(out);
      out.println();
      System.err.print(".");

      out.println(prefix + "STATE_INDEX");
      stateIndex.saveToWriter(out);
      out.println();
      System.err.print(".");

      out.println(prefix + "WORD_INDEX");
      wordIndex.saveToWriter(out);
      out.println();
      System.err.print(".");

      out.println(prefix + "TAG_INDEX");
      tagIndex.saveToWriter(out);
      out.println();
      System.err.print(".");

      String uwmClazz = ((lex.getUnknownWordModel() == null) ? "null" :
                   lex.getUnknownWordModel().getClass().getCanonicalName());
      out.println(prefix + "LEXICON " + uwmClazz);
      lex.writeData(out);
      out.println();
      System.err.print(".");

      out.println(prefix + "UNARY_GRAMMAR");
      ug.writeData(out);
      out.println();
      System.err.print(".");

      out.println(prefix + "BINARY_GRAMMAR");
      bg.writeData(out);
      out.println();
      System.err.print(".");

      out.println(prefix + "DEPENDENCY_GRAMMAR");
      if (dg != null) {
        dg.writeData(out);
      }
      out.println();
      System.err.print(".");

      out.flush();
      out.close();
      System.err.println("done.");
    } catch (IOException e) {
      System.err.println("Trouble saving parser data to ASCII format.");
      throw new RuntimeIOException(e);
    }
  }

  private static void confirmBeginBlock(String file, String line) {
    if (line == null) {
      throw new RuntimeException(file + ": expecting BEGIN block; got end of file.");
    } else if (! line.startsWith("BEGIN")) {
      throw new RuntimeException(file + ": expecting BEGIN block; got " + line);
    }
  }

  protected static LexicalizedParser getParserFromTextFile(String textFileOrUrl, Options op) {
    try {
      Timing tim = new Timing();
      System.err.print("Loading parser from text file " + textFileOrUrl + ' ');
      BufferedReader in = IOUtils.readReaderFromString(textFileOrUrl);
      Timing.startTime();

      String line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      op.readData(in);
      System.err.print(".");

      line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      Index<String> stateIndex = HashIndex.loadFromReader(in);
      System.err.print(".");

      line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      Index<String> wordIndex = HashIndex.loadFromReader(in);
      System.err.print(".");

      line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      Index<String> tagIndex = HashIndex.loadFromReader(in);
      System.err.print(".");

      line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      Lexicon lex = op.tlpParams.lex(op, wordIndex, tagIndex);
      String uwmClazz = line.split(" +")[2];
      if (!uwmClazz.equals("null")) {
        UnknownWordModel model = ReflectionLoading.loadByReflection(uwmClazz, op, lex, wordIndex, tagIndex);
        lex.setUnknownWordModel(model);
      }
      lex.readData(in);
      System.err.print(".");

      line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      UnaryGrammar ug = new UnaryGrammar(stateIndex);
      ug.readData(in);
      System.err.print(".");

      line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      BinaryGrammar bg = new BinaryGrammar(stateIndex);
      bg.readData(in);
      System.err.print(".");

      line = in.readLine();
      confirmBeginBlock(textFileOrUrl, line);
      DependencyGrammar dg = new MLEDependencyGrammar(op.tlpParams, op.directional, op.distance, op.coarseDistance, op.trainOptions.basicCategoryTagsInDependencyGrammar, op, wordIndex, tagIndex);
      dg.readData(in);
      System.err.print(".");

      in.close();
      System.err.println(" done [" + tim.toSecondsString() + " sec].");
      return new LexicalizedParser(lex, bg, ug, dg, stateIndex, wordIndex, tagIndex, op);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }


  public static LexicalizedParser getParserFromSerializedFile(String serializedFileOrUrl) {
    try {
      Timing tim = new Timing();
      System.err.print("Loading parser from serialized file " + serializedFileOrUrl + " ...");
      ObjectInputStream in = IOUtils.readStreamFromString(serializedFileOrUrl);
      LexicalizedParser pd = loadModel(in);

      in.close();
      System.err.println(" done [" + tim.toSecondsString() + " sec].");
      return pd;
    } catch (InvalidClassException ice) {
      // For this, it's not a good idea to continue and try it as a text file!
      System.err.println();   // as in middle of line from above message
      throw new RuntimeException("Invalid class in file: " + serializedFileOrUrl, ice);
    } catch (FileNotFoundException fnfe) {
      // For this, it's not a good idea to continue and try it as a text file!
      System.err.println();   // as in middle of line from above message
      throw new RuntimeException("File not found: " + serializedFileOrUrl, fnfe);
    } catch (StreamCorruptedException sce) {
      // suppress error message, on the assumption that we've really got
      // a text grammar, and that'll be tried next
      System.err.println();
    } catch (Exception e) {
      System.err.println();   // as in middle of line from above message
      e.printStackTrace();
    }
    return null;
  }


  private static void printOptions(boolean train, Options op) {
    op.display();
    if (train) {
      op.trainOptions.display();
    } else {
      op.testOptions.display();
    }
    op.tlpParams.display();
  }


  // TODO: Make below method work with arbitrarily large secondary treebank via iteration
  // TODO: Have weight implemented for training lexicon

  /**
   * A method for training from two different treebanks, the second of which is presumed
   * to be orders of magnitude larger.
   * <p/>
   * Trees are not read into memory but processed as they are read from disk.
   * <p/>
   * A weight (typically &lt;= 1) can be put on the second treebank.
   *
   * @param trainTreebank A treebank to train from
   * @param secondaryTrainTreebank Another treebank to train from
   * @param weight A weight factor to give the secondary treebank. If the weight
   *     is 0.25, each example in the secondaryTrainTreebank will be treated as
   *     1/4 of an example sentence.
   * @param compactor A class for compacting grammars. May be null.
   * @param op Options for how the grammar is built from the treebank
   * @param tuneTreebank  A treebank to tune free params on (may be null)
   * @param extraTaggedWords A list of words to add to the Lexicon
   * @return The trained LexicalizedParser
   */
  public static LexicalizedParser
  getParserFromTreebank(Treebank trainTreebank,
                        Treebank secondaryTrainTreebank,
                        double weight,
                        GrammarCompactor compactor,
                        Options op,
                        Treebank tuneTreebank,
                        List<List<TaggedWord>> extraTaggedWords)
  {
    // System.err.println("Currently " + new Date()); // now printed when command-line args are printed
    printOptions(true, op);
    Timing.startTime();

    Triple<Treebank, Treebank, Treebank> treebanks = TreeAnnotatorAndBinarizer.getAnnotatedBinaryTreebankFromTreebank(trainTreebank, secondaryTrainTreebank, tuneTreebank, op);
    Timing.tick("done.");

    Treebank trainTreebankRaw = trainTreebank;
    trainTreebank = treebanks.first();
    secondaryTrainTreebank = treebanks.second();
    tuneTreebank = treebanks.third();

    // +1 to account for the boundary symbol
    trainTreebank = new FilteringTreebank(trainTreebank, new LengthTreeFilter(op.trainOptions.trainLengthLimit + 1));
    if (secondaryTrainTreebank != null) {
      secondaryTrainTreebank = new FilteringTreebank(secondaryTrainTreebank, new LengthTreeFilter(op.trainOptions.trainLengthLimit + 1));
    }
    if (tuneTreebank != null) {
      tuneTreebank = new FilteringTreebank(tuneTreebank, new LengthTreeFilter(op.trainOptions.trainLengthLimit + 1));
    }

    Index<String> stateIndex;
    Index<String> wordIndex;
    Index<String> tagIndex;

    Pair<UnaryGrammar, BinaryGrammar> bgug;
    Lexicon lex;

    if (op.trainOptions.predictSplits) {
      SplittingGrammarExtractor extractor = new SplittingGrammarExtractor(op);
      System.err.print("Extracting PCFG...");
      // TODO: make use of the tagged text
      if (secondaryTrainTreebank == null) {
        extractor.extract(trainTreebank);
      } else {
        extractor.extract(trainTreebank, 1.0, secondaryTrainTreebank, weight);
      }
      bgug = extractor.bgug;
      lex = extractor.lex;
      stateIndex = extractor.stateIndex;
      wordIndex = extractor.wordIndex;
      tagIndex = extractor.tagIndex;
      Timing.tick("done.");
    } else {
      stateIndex = new HashIndex<String>();
      wordIndex = new HashIndex<String>();
      tagIndex = new HashIndex<String>();

      // extract grammars
      BinaryGrammarExtractor bgExtractor = new BinaryGrammarExtractor(op, stateIndex);
      // Extractor lexExtractor = new LexiconExtractor();
      //TreeExtractor uwmExtractor = new UnknownWordModelExtractor(trainTreebank.size());
      System.err.print("Extracting PCFG...");
      if (secondaryTrainTreebank == null) {
        bgug = bgExtractor.extract(trainTreebank);
      } else {
        bgug = bgExtractor.extract(trainTreebank, 1.0,
                                   secondaryTrainTreebank, weight);
      }
      Timing.tick("done.");

      System.err.print("Extracting Lexicon...");
      lex = op.tlpParams.lex(op, wordIndex, tagIndex);

      double trainSize = trainTreebank.size();
      if (secondaryTrainTreebank != null) {
        trainSize += (secondaryTrainTreebank.size() * weight);
      }
      if (extraTaggedWords != null) {
        trainSize += extraTaggedWords.size();
      }

      lex.initializeTraining(trainSize);
      // wsg2012: The raw treebank has CoreLabels, which we need for FactoredLexicon
      // training. If TreeAnnotator is updated so that it produces CoreLabels, then we can
      // remove the trainTreebankRaw.
      lex.train(trainTreebank, trainTreebankRaw);
      if (secondaryTrainTreebank != null) {
        lex.train(secondaryTrainTreebank, weight);
      }
      if (extraTaggedWords != null) {
        for (List<TaggedWord> sentence : extraTaggedWords) {
          // TODO: specify a weight?
          lex.trainUnannotated(sentence, 1.0);
        }
      }
      lex.finishTraining();
      Timing.tick("done.");
    }

    //TODO: wsg2011 Not sure if this should come before or after
    //grammar compaction
    if (op.trainOptions.ruleSmoothing) {
      System.err.print("Smoothing PCFG...");
      Function<Pair<UnaryGrammar,BinaryGrammar>,Pair<UnaryGrammar,BinaryGrammar>> smoother = new LinearGrammarSmoother(op.trainOptions, stateIndex, tagIndex);
      bgug = smoother.apply(bgug);
      Timing.tick("done.");
    }

    if (compactor != null) {
      System.err.print("Compacting grammar...");
      Triple<Index<String>, UnaryGrammar, BinaryGrammar> compacted = compactor.compactGrammar(bgug, stateIndex);
      stateIndex = compacted.first();
      bgug.setFirst(compacted.second());
      bgug.setSecond(compacted.third());
      Timing.tick("done.");
    }

    System.err.print("Compiling grammar...");
    BinaryGrammar bg = bgug.second;
    bg.splitRules();
    UnaryGrammar ug = bgug.first;
    ug.purgeRules();
    Timing.tick("done");

    DependencyGrammar dg = null;
    if (op.doDep) {
      System.err.print("Extracting Dependencies...");
      AbstractTreeExtractor<DependencyGrammar> dgExtractor = new MLEDependencyGrammarExtractor(op, wordIndex, tagIndex);
      if (secondaryTrainTreebank == null) {
        dg = dgExtractor.extract(trainTreebank);
      } else {
        dg = dgExtractor.extract(trainTreebank, 1.0, secondaryTrainTreebank, weight);
      }
      //System.err.print("Extracting Unknown Word Model...");
      //UnknownWordModel uwm = (UnknownWordModel)uwmExtractor.extract(trainTreebank);
      //Timing.tick("done.");
      Timing.tick("done.");
      if (tuneTreebank != null) {
        System.err.print("Tuning Dependency Model...");
        dg.setLexicon(lex); // MG2008: needed if using PwGt classify
        dg.tune(tuneTreebank);
        Timing.tick("done.");
      }
    }

    System.err.println("Done training parser.");
    if (op.trainOptions.trainTreeFile!=null) {
      try {
        System.err.print("Writing out binary trees to "+ op.trainOptions.trainTreeFile+"...");
        IOUtils.writeObjectToFile(trainTreebank, op.trainOptions.trainTreeFile);
        IOUtils.writeObjectToFile(secondaryTrainTreebank, op.trainOptions.trainTreeFile);
        Timing.tick("done.");
      } catch (Exception e) {
        System.err.println("Problem writing out binary trees.");
      }
    }
    return new LexicalizedParser(lex, bg, ug, dg, stateIndex, wordIndex, tagIndex, op);
  }


  /**
   * This will set options to the parser, in a way exactly equivalent to
   * passing in the same sequence of command-line arguments.  This is a useful
   * convenience method when building a parser programmatically. The options
   * passed in should
   * be specified like command-line arguments, including with an initial
   * minus sign.
   * <p/>
   * <i>Notes:</i> This can be used to set parsing-time flags for a
   * serialized parser.  You can also still change things serialized
   * in Options, but this will probably degrade parsing performance.
   * The vast majority of command line flags can be passed to this
   * method, but you cannot pass in options that specify the treebank
   * or grammar to be loaded, the grammar to be written, trees or
   * files to be parsed or details of their encoding, nor the
   * TreebankLangParserParams (<code>-tLPP</code>) to use. The
   * TreebankLangParserParams should be set up on construction of a
   * LexicalizedParser, by constructing an Options that uses
   * the required TreebankLangParserParams, and passing that to a
   * LexicalizedParser constructor.  Note that despite this
   * method being an instance method, many flags are actually set as
   * static class variables.
   *
   * @param flags Arguments to the parser, for example,
   *              {"-outputFormat", "typedDependencies", "-maxLength", "70"}
   * @throws IllegalArgumentException If an unknown flag is passed in
   */
  public void setOptionFlags(String... flags) {
    op.setOptions(flags);
  }


  /**
   * A main program for using the parser with various options.
   * This program can be used for building and serializing
   * a parser from treebank data, for parsing sentences from a file
   * or URL using a serialized or text grammar parser,
   * and (mainly for parser quality testing)
   * for training and testing a parser on a treebank all in one go.
   *
   * <p>
   * Sample Usages:
   * <ul>
   *   <li> <b>Train a parser (saved to <i>serializedGrammarFilename</i>)
   *      from a directory of trees (<i>trainFilesPath</i>, with an optional <i>fileRange</i>, e.g., 0-1000):</b>
   *    <code>java -mx1500m edu.stanford.nlp.parser.lexparser.LexicalizedParser [-v] -train trainFilesPath [fileRange] -saveToSerializedFile serializedGrammarFilename</code>
   *   </li>
   *
   *   <li> <b>Train a parser (not saved) from a directory of trees, and test it (reporting scores) on a directory of trees</b>
   *    <code> java -mx1500m edu.stanford.nlp.parser.lexparser.LexicalizedParser [-v] -train trainFilesPath [fileRange] -testTreebank testFilePath [fileRange] </code>
   *   </li>
   *
   *   <li> <b>Parse one or more files, given a serialized grammar and a list of files</b>
   *    <code>java -mx512m edu.stanford.nlp.parser.lexparser.LexicalizedParser [-v] serializedGrammarPath filename [filename] ...</code>
   *   </li>
   *
   *   <li> <b>Test and report scores for a serialized grammar on trees in an output directory</b>
   *    <code>java -mx512m edu.stanford.nlp.parser.lexparser.LexicalizedParser [-v] -loadFromSerializedFile serializedGrammarPath -testTreebank testFilePath [fileRange]</code>
   *   </li>
   * </ul>
   *
   *<p>
   * If the <code>serializedGrammarPath</code> ends in <code>.gz</code>,
   * then the grammar is written and read as a compressed file (GZip).
   * If the <code>serializedGrammarPath</code> is a URL, starting with
   * <code>http://</code>, then the parser is read from the URL.
   * A fileRange specifies a numeric value that must be included within a
   * filename for it to be used in training or testing (this works well with
   * most current treebanks).  It can be specified like a range of pages to be
   * printed, for instance as <code>200-2199</code> or
   * <code>1-300,500-725,9000</code> or just as <code>1</code> (if all your
   * trees are in a single file, just give a dummy argument such as
   * <code>0</code> or <code>1</code>).
   * The parser can write a grammar as either a serialized Java object file
   * or in a text format (or as both), specified with the following options:
   *
   * <p>
   * <code>java edu.stanford.nlp.parser.lexparser.LexicalizedParser
   * [-v] -train
   * trainFilesPath [fileRange] [-saveToSerializedFile grammarPath]
   * [-saveToTextFile grammarPath]</code><p>
   * If no files are supplied to parse, then a hardwired sentence
   * is parsed. <p>
   *
   * In the same position as the verbose flag (<code>-v</code>), many other
   * options can be specified.  The most useful to an end user are:
   * <UL>
   * <LI><code>-tLPP class</code> Specify a different
   * TreebankLangParserParams, for when using a different language or
   * treebank (the default is English Penn Treebank). <i>This option MUST occur
   * before any other language-specific options that are used (or else they
   * are ignored!).</i>
   * (It's usually a good idea to specify this option even when loading a
   * serialized grammar; it is necessary if the language pack specifies a
   * needed character encoding or you wish to specify language-specific
   * options on the command line.)</LI>
   * <LI><code>-encoding charset</code> Specify the character encoding of the
   * input and output files.  This will override the value in the
   * <code>TreebankLangParserParams</code>, provided this option appears
   * <i>after</i> any <code>-tLPP</code> option.</LI>
   * <LI><code>-tokenized</code> Says that the input is already separated
   * into whitespace-delimited tokens.  If this option is specified, any
   * tokenizer specified for the language is ignored, and a universal (Unicode)
   * tokenizer, which divides only on whitespace, is used.
   * Unless you also specify
   * <code>-escaper</code>, the tokens <i>must</i> all be correctly
   * tokenized tokens of the appropriate treebank for the parser to work
   * well (for instance, if using the Penn English Treebank, you must have
   * coded "(" as "-LRB-", "3/4" as "3\/4", etc.)</LI>
   * <li><code>-escaper class</code> Specify a class of type
   * {@link Function}&lt;List&lt;HasWord&gt;,List&lt;HasWord&gt;&gt; to do
   * customized escaping of tokenized text.  This class will be run over the
   * tokenized text and can fix the representation of tokens. For instance,
   * it could change "(" to "-LRB-" for the Penn English Treebank.  A
   * provided escaper that does such things for the Penn English Treebank is
   * <code>edu.stanford.nlp.process.PTBEscapingProcessor</code>
   * <li><code>-tokenizerFactory class</code> Specifies a
   * TokenizerFactory class to be used for tokenization</li>
   * <li><code>-tokenizerOptions options</code> Specifies options to a
   * TokenizerFactory class to be used for tokenization.   A comma-separated
   * list. For PTBTokenizer, options of interest include
   * <code>americanize=false</code> and <code>asciiQuotes</code> (for German).
   * Note that any choice of tokenizer options that conflicts with the
   * tokenization used in the parser training data will likely degrade parser
   * performance. </li>
   * <li><code>-sentences token </code> Specifies a token that marks sentence
   * boundaries.  A value of <code>newline</code> causes sentence breaking on
   * newlines.  A value of <code>onePerElement</code> causes each element
   * (using the XML <code>-parseInside</code> option) to be treated as a
   * sentence. All other tokens will be interpreted literally, and must be
   * exactly the same as tokens returned by the tokenizer.  For example,
   * you might specify "|||" and put that symbol sequence as a token between
   * sentences.
   * If no explicit sentence breaking option is chosen, sentence breaking
   * is done based on a set of language-particular sentence-ending patterns.
   * </li>
   * <LI><code>-parseInside element</code> Specifies that parsing should only
   * be done for tokens inside the indicated XML-style
   * elements (done as simple pattern matching, rather than XML parsing).
   * For example, if this is specified as <code>sentence</code>, then
   * the text inside the <code>sentence</code> element
   * would be parsed.
   * Using "-parseInside s" gives you support for the input format of
   * Charniak's parser. Sentences cannot span elements. Whether the
   * contents of the element are treated as one sentence or potentially
   * multiple sentences is controlled by the <code>-sentences</code> flag.
   * The default is potentially multiple sentences.
   * This option gives support for extracting and parsing
   * text from very simple SGML and XML documents, and is provided as a
   * user convenience for that purpose. If you want to really parse XML
   * documents before NLP parsing them, you should use an XML parser, and then
   * call to a LexicalizedParser on appropriate CDATA.
   * <LI><code>-tagSeparator char</code> Specifies to look for tags on words
   * following the word and separated from it by a special character
   * <code>char</code>.  For instance, many tagged corpora have the
   * representation "house/NN" and you would use <code>-tagSeparator /</code>.
   * Notes: This option requires that the input be pretokenized.
   * The separator has to be only a single character, and there is no
   * escaping mechanism. However, splitting is done on the <i>last</i>
   * instance of the character in the token, so that cases like
   * "3\/4/CD" are handled correctly.  The parser will in all normal
   * circumstances use the tag you provide, but will override it in the
   * case of very common words in cases where the tag that you provide
   * is not one that it regards as a possible tagging for the word.
   * The parser supports a format where only some of the words in a sentence
   * have a tag (if you are calling the parser programmatically, you indicate
   * them by having them implement the <code>HasTag</code> interface).
   * You can do this at the command-line by only having tags after some words,
   * but you are limited by the fact that there is no way to escape the
   * tagSeparator character.</LI>
   * <LI><code>-maxLength leng</code> Specify the longest sentence that
   * will be parsed (and hence indirectly the amount of memory
   * needed for the parser). If this is not specified, the parser will
   * try to dynamically grow its parse chart when long sentence are
   * encountered, but may run out of memory trying to do so.</LI>
   * <LI><code>-outputFormat styles</code> Choose the style(s) of output
   * sentences: <code>penn</code> for prettyprinting as in the Penn
   * treebank files, or <code>oneline</code> for printing sentences one
   * per line, <code>words</code>, <code>wordsAndTags</code>,
   * <code>dependencies</code>, <code>typedDependencies</code>,
   * or <code>typedDependenciesCollapsed</code>.
   * Multiple options may be specified as a comma-separated
   * list.  See TreePrint class for further documentation.</LI>
   * <LI><code>-outputFormatOptions</code> Provide options that control the
   * behavior of various <code>-outputFormat</code> choices, such as
   * <code>lexicalize</code>, <code>stem</code>, <code>markHeadNodes</code>,
   * or <code>xml</code>.
   * Options are specified as a comma-separated list.</LI>
   * <LI><code>-writeOutputFiles</code> Write output files corresponding
   * to the input files, with the same name but a <code>".stp"</code>
   * file extension.  The format of these files depends on the
   * <code>outputFormat</code> option.  (If not specified, output is sent
   * to stdout.)</LI>
   * <LI><code>-outputFilesExtension</code> The extension that is appended to
   * the filename that is being parsed to produce an output file name (with the
   * -writeOutputFiles option). The default is <code>stp</code>.  Don't
   * include the period.
   * <LI><code>-outputFilesDirectory</code> The directory in which output
   * files are written (when the -writeOutputFiles option is specified).
   * If not specified, output files are written in the same directory as the
   * input files.
   * </UL>
   * See also the package documentation for more details and examples of use.
   *
   * @param args Command line arguments, as above
   */
  public static void main(String[] args) {
    boolean train = false;
    boolean saveToSerializedFile = false;
    boolean saveToTextFile = false;
    String serializedInputFileOrUrl = null;
    String textInputFileOrUrl = null;
    String serializedOutputFileOrUrl = null;
    String textOutputFileOrUrl = null;
    String treebankPath = null;
    Treebank testTreebank = null;
    Treebank tuneTreebank = null;
    String testPath = null;
    FileFilter testFilter = null;
    String tunePath = null;
    FileFilter tuneFilter = null;
    FileFilter trainFilter = null;
    String secondaryTreebankPath = null;
    double secondaryTreebankWeight = 1.0;
    FileFilter secondaryTrainFilter = null;

    // variables needed to process the files to be parsed
    TokenizerFactory<? extends HasWord> tokenizerFactory = null;
    String tokenizerOptions = null;
    String tokenizerFactoryClass = null;
    String tokenizerMethod = null;
    boolean tokenized = false; // whether or not the input file has already been tokenized
    Function<List<HasWord>, List<HasWord>> escaper = null;
    String tagDelimiter = null;
    String sentenceDelimiter = null;
    String elementDelimiter = null;
    int argIndex = 0;
    if (args.length < 1) {
      System.err.println("Basic usage (see Javadoc for more): java edu.stanford.nlp.parser.lexparser.LexicalizedParser parserFileOrUrl filename*");
      return;
    }

    Options op = new Options();
    ArrayList<String> optionArgs = new ArrayList<String>();
    String encoding = null;
    // while loop through option arguments
    while (argIndex < args.length && args[argIndex].charAt(0) == '-') {
      if (args[argIndex].equalsIgnoreCase("-train") ||
          args[argIndex].equalsIgnoreCase("-trainTreebank")) {
        train = true;
        Pair<String, FileFilter> treebankDescription = ArgUtils.getTreebankDescription(args, argIndex, "-test");
        argIndex = argIndex + ArgUtils.numSubArgs(args, argIndex) + 1;
        treebankPath = treebankDescription.first();
        trainFilter = treebankDescription.second();
      } else if (args[argIndex].equalsIgnoreCase("-train2")) {
        // TODO: we could use the fully expressive -train options if
        // we add some mechanism for returning leftover options from
        // ArgUtils.getTreebankDescription
        // train = true;     // cdm july 2005: should require -train for this
        int numSubArgs = ArgUtils.numSubArgs(args, argIndex);
        argIndex++;
        if (numSubArgs < 2) {
          throw new RuntimeException("Error: -train2 <treebankPath> [<ranges>] <weight>.");
        }
        secondaryTreebankPath = args[argIndex++];
        secondaryTrainFilter = (numSubArgs == 3) ? new NumberRangesFileFilter(args[argIndex++], true) : null;
        secondaryTreebankWeight = Double.parseDouble(args[argIndex++]);
      } else if (args[argIndex].equalsIgnoreCase("-tLPP") && (argIndex + 1 < args.length)) {
        try {
          op.tlpParams = (TreebankLangParserParams) Class.forName(args[argIndex + 1]).newInstance();
        } catch (ClassNotFoundException e) {
          System.err.println("Class not found: " + args[argIndex + 1]);
          throw new RuntimeException(e);
        } catch (InstantiationException e) {
          System.err.println("Couldn't instantiate: " + args[argIndex + 1] + ": " + e.toString());
          throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
          System.err.println("Illegal access" + e);
          throw new RuntimeException(e);
        }
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-encoding")) {
        // sets encoding for TreebankLangParserParams
        // redone later to override any serialized parser one read in
        encoding = args[argIndex + 1];
        op.tlpParams.setInputEncoding(encoding);
        op.tlpParams.setOutputEncoding(encoding);
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-tokenized")) {
        tokenized = true;
        argIndex += 1;
      } else if (args[argIndex].equalsIgnoreCase("-escaper")) {
        try {
          escaper = ReflectionLoading.loadByReflection(args[argIndex + 1]);
        } catch (Exception e) {
          System.err.println("Couldn't instantiate escaper " + args[argIndex + 1] + ": " + e);
        }
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-tokenizerOptions")) {
        tokenizerOptions = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-tokenizerFactory")) {
        tokenizerFactoryClass = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-tokenizerMethod")) {
        tokenizerMethod = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-sentences")) {
        sentenceDelimiter = args[argIndex + 1];
        if (sentenceDelimiter.equalsIgnoreCase("newline")) {
          sentenceDelimiter = "\n";
        }
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-parseInside")) {
        elementDelimiter = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-tagSeparator")) {
        tagDelimiter = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-loadFromSerializedFile") ||
                 args[argIndex].equalsIgnoreCase("-classify")) {
        // load the parser from a binary serialized file
        // the next argument must be the path to the parser file
        serializedInputFileOrUrl = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-loadFromTextFile")) {
        // load the parser from declarative text file
        // the next argument must be the path to the parser file
        textInputFileOrUrl = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-saveToSerializedFile")) {
        saveToSerializedFile = true;
        if (ArgUtils.numSubArgs(args, argIndex) < 1) {
          System.err.println("Missing path: -saveToSerialized filename");
        } else {
          serializedOutputFileOrUrl = args[argIndex + 1];
        }
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-saveToTextFile")) {
        // save the parser to declarative text file
        saveToTextFile = true;
        textOutputFileOrUrl = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-saveTrainTrees")) {
        // save the training trees to a binary file
        op.trainOptions.trainTreeFile = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-treebank") ||
                 args[argIndex].equalsIgnoreCase("-testTreebank") ||
                 args[argIndex].equalsIgnoreCase("-test")) {
        Pair<String, FileFilter> treebankDescription = ArgUtils.getTreebankDescription(args, argIndex, "-test");
        argIndex = argIndex + ArgUtils.numSubArgs(args, argIndex) + 1;
        testPath = treebankDescription.first();
        testFilter = treebankDescription.second();
      } else if (args[argIndex].equalsIgnoreCase("-tune")) {
        Pair<String, FileFilter> treebankDescription = ArgUtils.getTreebankDescription(args, argIndex, "-tune");
        argIndex = argIndex + ArgUtils.numSubArgs(args, argIndex) + 1;
        tunePath = treebankDescription.first();
        tuneFilter = treebankDescription.second();
      } else {
        int oldIndex = argIndex;
        argIndex = op.setOptionOrWarn(args, argIndex);
        for (int i = oldIndex; i < argIndex; ++i) {
          optionArgs.add(args[i]);
        }
      }
    } // end while loop through arguments

    // set up tokenizerFactory with options if provided
    if (tokenizerFactoryClass != null || tokenizerOptions != null) {
      try {
        if (tokenizerFactoryClass != null) {
          Class<TokenizerFactory<? extends HasWord>> clazz = ErasureUtils.uncheckedCast(Class.forName(tokenizerFactoryClass));
          Method factoryMethod;
          if (tokenizerOptions != null) {
            factoryMethod = clazz.getMethod(tokenizerMethod != null ? tokenizerMethod : "newWordTokenizerFactory", String.class);
            tokenizerFactory = ErasureUtils.uncheckedCast(factoryMethod.invoke(null, tokenizerOptions));
          } else {
            factoryMethod = clazz.getMethod(tokenizerMethod != null ? tokenizerMethod : "newTokenizerFactory");
            tokenizerFactory = ErasureUtils.uncheckedCast(factoryMethod.invoke(null));
          }
        } else {
          // have options but no tokenizer factory; default to PTB
          tokenizerFactory = PTBTokenizer.PTBTokenizerFactory.newWordTokenizerFactory(tokenizerOptions);
        }
      } catch (IllegalAccessException e) {
        System.err.println("Couldn't instantiate TokenizerFactory " + tokenizerFactoryClass + " with options " + tokenizerOptions);
        throw new RuntimeException(e);
      } catch (NoSuchMethodException e) {
        System.err.println("Couldn't instantiate TokenizerFactory " + tokenizerFactoryClass + " with options " + tokenizerOptions);
        throw new RuntimeException(e);
      } catch (ClassNotFoundException e) {
        System.err.println("Couldn't instantiate TokenizerFactory " + tokenizerFactoryClass + " with options " + tokenizerOptions);
        throw new RuntimeException(e);
      } catch (InvocationTargetException e) {
        System.err.println("Couldn't instantiate TokenizerFactory " + tokenizerFactoryClass + " with options " + tokenizerOptions);
        throw new RuntimeException(e);
      }
    }

    // all other arguments are order dependent and
    // are processed in order below

    if (tuneFilter != null || tunePath != null) {
      if (tunePath == null) {
        if (treebankPath == null) {
          throw new RuntimeException("No tune treebank path specified...");
        } else {
          System.err.println("No tune treebank path specified.  Using train path: \"" + treebankPath + '\"');
          tunePath = treebankPath;
        }
      }
      tuneTreebank = op.tlpParams.testMemoryTreebank();
      tuneTreebank.loadPath(tunePath, tuneFilter);
    }

    if (!train && op.testOptions.verbose) {
      StringUtils.printErrInvocationString("LexicalizedParser", args);
    }
    LexicalizedParser lp; // always initialized in next if-then-else block
    if (train) {
      StringUtils.printErrInvocationString("LexicalizedParser", args);

      // so we train a parser using the treebank
      GrammarCompactor compactor = null;
      if (op.trainOptions.compactGrammar() == 3) {
        compactor = new ExactGrammarCompactor(op, false, false);
      }

      Treebank trainTreebank = makeTreebank(treebankPath, op, trainFilter);

      Treebank secondaryTrainTreebank = null;
      if (secondaryTreebankPath != null) {
        secondaryTrainTreebank = makeSecondaryTreebank(secondaryTreebankPath, op, secondaryTrainFilter);
      }

      List<List<TaggedWord>> extraTaggedWords = null;
      if (op.trainOptions.taggedFiles != null) {
        extraTaggedWords = new ArrayList<List<TaggedWord>>();
        List<TaggedFileRecord> fileRecords = TaggedFileRecord.createRecords(new Properties(), op.trainOptions.taggedFiles);
        for (TaggedFileRecord record : fileRecords) {
          for (List<TaggedWord> sentence : record.reader()) {
            extraTaggedWords.add(sentence);
          }
        }
      }

      lp = getParserFromTreebank(trainTreebank, secondaryTrainTreebank, secondaryTreebankWeight, compactor, op, tuneTreebank, extraTaggedWords);
    } else if (textInputFileOrUrl != null) {
      // so we load the parser from a text grammar file
      lp = getParserFromTextFile(textInputFileOrUrl, op);
    } else {
      // so we load a serialized parser
      if (serializedInputFileOrUrl == null && argIndex < args.length) {
        // the next argument must be the path to the serialized parser
        serializedInputFileOrUrl = args[argIndex];
        argIndex++;
      }
      if (serializedInputFileOrUrl == null) {
        System.err.println("No grammar specified, exiting...");
        return;
      }
      String[] extraArgs = new String[optionArgs.size()];
      extraArgs = optionArgs.toArray(extraArgs);
      try {
        lp = loadModel(serializedInputFileOrUrl, op, extraArgs);
        op = lp.op;
      } catch (IllegalArgumentException e) {
        System.err.println("Error loading parser, exiting...");
        throw e;
      }
    }

    // the following has to go after reading parser to make sure
    // op and tlpParams are the same for train and test
    // THIS IS BUTT UGLY BUT IT STOPS USER SPECIFIED ENCODING BEING
    // OVERWRITTEN BY ONE SPECIFIED IN SERIALIZED PARSER
    if (encoding != null) {
      op.tlpParams.setInputEncoding(encoding);
      op.tlpParams.setOutputEncoding(encoding);
    }

    if (testFilter != null || testPath != null) {
      if (testPath == null) {
        if (treebankPath == null) {
          throw new RuntimeException("No test treebank path specified...");
        } else {
          System.err.println("No test treebank path specified.  Using train path: \"" + treebankPath + '\"');
          testPath = treebankPath;
        }
      }
      testTreebank = op.tlpParams.testMemoryTreebank();
      testTreebank.loadPath(testPath, testFilter);
    }

    op.trainOptions.sisterSplitters = new HashSet<String>(Arrays.asList(op.tlpParams.sisterSplitters()));

    // at this point we should be sure that op.tlpParams is
    // set appropriately (from command line, or from grammar file),
    // and will never change again.  -- Roger

    // Now what do we do with the parser we've made
    if (saveToTextFile) {
      // save the parser to textGrammar format
      if (textOutputFileOrUrl != null) {
        lp.saveParserToTextFile(textOutputFileOrUrl);
      } else {
        System.err.println("Usage: must specify a text grammar output path");
      }
    }
    if (saveToSerializedFile) {
      if (serializedOutputFileOrUrl != null) {
        lp.saveParserToSerialized(serializedOutputFileOrUrl);
      } else if (textOutputFileOrUrl == null && testTreebank == null) {
        // no saving/parsing request has been specified
        System.err.println("usage: " + "java edu.stanford.nlp.parser.lexparser.LexicalizedParser " + "-train trainFilesPath [fileRange] -saveToSerializedFile serializedParserFilename");
      }
    }

    if (op.testOptions.verbose || train) {
      // Tell the user a little or a lot about what we have made
      // get lexicon size separately as it may have its own prints in it....
      String lexNumRules = lp.lex != null ? Integer.toString(lp.lex.numRules()): "";
      System.err.println("Grammar\tStates\tTags\tWords\tUnaryR\tBinaryR\tTaggings");
      System.err.println("Grammar\t" +
          lp.stateIndex.size() + '\t' +
          lp.tagIndex.size() + '\t' +
          lp.wordIndex.size() + '\t' +
          (lp.ug != null ? lp.ug.numRules(): "") + '\t' +
          (lp.bg != null ? lp.bg.numRules(): "") + '\t' +
          lexNumRules);
      System.err.println("ParserPack is " + op.tlpParams.getClass().getName());
      System.err.println("Lexicon is " + lp.lex.getClass().getName());
      if (op.testOptions.verbose) {
        System.err.println("Tags are: " + lp.tagIndex);
        // System.err.println("States are: " + lp.pd.stateIndex); // This is too verbose. It was already printed out by the below printOptions command if the flag -printStates is given (at training time)!
      }
      printOptions(false, op);
    }

    if (testTreebank != null) {
      // test parser on treebank
      EvaluateTreebank evaluator = new EvaluateTreebank(lp);
      evaluator.testOnTreebank(testTreebank);
    } else if (argIndex >= args.length) {
      // no more arguments, so we just parse our own test sentence
      PrintWriter pwOut = op.tlpParams.pw();
      PrintWriter pwErr = op.tlpParams.pw(System.err);
      ParserQuery pq = lp.parserQuery();
      if (pq.parse(op.tlpParams.defaultTestSentence())) {
        lp.getTreePrint().printTree(pq.getBestParse(), pwOut);
      } else {
        pwErr.println("Error. Can't parse test sentence: " +
                      op.tlpParams.defaultTestSentence());
      }
    } else {
      // We parse filenames given by the remaining arguments
      ParseFiles.parseFiles(args, argIndex, tokenized, tokenizerFactory, elementDelimiter, sentenceDelimiter, escaper, tagDelimiter, op, lp.getTreePrint(), lp);
    }

  } // end main

  private static final long serialVersionUID = 2;

} // end class LexicalizedParser

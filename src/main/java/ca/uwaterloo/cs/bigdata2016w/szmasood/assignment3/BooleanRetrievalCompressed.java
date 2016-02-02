package ca.uwaterloo.cs.bigdata2016w.szmasood.assignment3;

import java.io.*;
import java.util.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.xerces.impl.xs.SchemaSymbols;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;

import tl.lin.data.array.ArrayListWritable;
import tl.lin.data.pair.PairOfInts;
import tl.lin.data.pair.PairOfWritables;

public class BooleanRetrievalCompressed extends Configured implements Tool {
  private HashMap<String,MapFile.Reader> index = new HashMap<>();
  private FSDataInputStream collection;
  private Stack<Set<Integer>> stack;

  private BooleanRetrievalCompressed() {}


  private void initialize(ArrayList<String> fileList, String indexPath, String collectionPath, FileSystem fs) throws IOException {
    for (String fl: fileList) {
      index.put(fl, new MapFile.Reader(new Path(indexPath + "/" + fl.trim()), fs.getConf()));
    }
    collection = fs.open(new Path(collectionPath));
    stack = new Stack<Set<Integer>>();
  }

  private void runQuery(String q) throws IOException {
    String[] terms = q.split("\\s+");

    for (String t : terms) {
      if (t.equals("AND")) {
        performAND();
      } else if (t.equals("OR")) {
        performOR();
      } else {
        pushTerm(t);
      }
    }

    Set<Integer> set = stack.pop();

    for (Integer i : set) {
      String line = fetchLine(i);
      System.out.println(i + "\t" + line);
    }
  }

  private void pushTerm(String term) throws IOException {
    stack.push(fetchDocumentSet(term));
  }

  private void performAND() {
    Set<Integer> s1 = stack.pop();
    Set<Integer> s2 = stack.pop();

    Set<Integer> sn = new TreeSet<Integer>();

    for (int n : s1) {
      if (s2.contains(n)) {
        sn.add(n);
      }
    }

    stack.push(sn);
  }

  private void performOR() {
    Set<Integer> s1 = stack.pop();
    Set<Integer> s2 = stack.pop();

    Set<Integer> sn = new TreeSet<Integer>();

    for (int n : s1) {
      sn.add(n);
    }

    for (int n : s2) {
      sn.add(n);
    }

    stack.push(sn);
  }

  private Set<Integer> fetchDocumentSet(String term) throws IOException {
    Set<Integer> set = new TreeSet<Integer>();

    for (PairOfInts pair : fetchPostings(term)) {
      set.add(pair.getLeftElement());
    }

    return set;
  }

  private ArrayListWritable<PairOfInts> fetchPostings(String term) throws IOException {
    Text key = new Text();
    BytesWritable value = new BytesWritable();
    int hashVal = (term.hashCode() & Integer.MAX_VALUE) % index.size();
    key.set(term);
    index.get("part-r-0000" + hashVal).get(key, value);
    ArrayListWritable<PairOfInts> postings = new ArrayListWritable<>();

    InputStream is = new ByteArrayInputStream(value.getBytes());
    DataInputStream inputBuffer = new DataInputStream(is);

    int df = WritableUtils.readVInt(inputBuffer);

    int a = 0;
    int b = 0;
    for (int i = 0; i < df; i++) {
      a += WritableUtils.readVInt(inputBuffer);
      b = WritableUtils.readVInt(inputBuffer);
      postings.add(new PairOfInts(a,b));
    }

    return postings;
  }

  private String fetchLine(long offset) throws IOException {
    collection.seek(offset);
    BufferedReader reader = new BufferedReader(new InputStreamReader(collection));

    return reader.readLine();
  }

  public static class Args {
    @Option(name = "-index", metaVar = "[path]", required = true, usage = "index path")
    public String index;

    @Option(name = "-collection", metaVar = "[path]", required = true, usage = "collection path")
    public String collection;

    @Option(name = "-query", metaVar = "[term]", required = true, usage = "query")
    public String query;
  }

  /**
   * Runs this tool.
   */
  public int run(String[] argv) throws Exception {
    Args args = new Args();
    CmdLineParser parser = new CmdLineParser(args, ParserProperties.defaults().withUsageWidth(100));

    try {
      parser.parseArgument(argv);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      return -1;
    }

    if (args.collection.endsWith(".gz")) {
      System.out.println("gzipped collection is not seekable: use compressed version!");
      return -1;
    }

    FileSystem fs = FileSystem.get(new Configuration());

    ArrayList<String> fileList = new ArrayList<String>();
    FileStatus[] fileStatus = fs.listStatus(new Path(args.index));
    for (FileStatus fileStat : fileStatus) {
      if (fileStat.isDirectory()) {
        fileList.add(fileStat.getPath().getName());
      }
    }

    initialize(fileList, args.index, args.collection, fs);

    System.out.println("Query: " + args.query);
    long startTime = System.currentTimeMillis();
    runQuery(args.query);
    System.out.println("\nquery completed in " + (System.currentTimeMillis() - startTime) + "ms");

    return 1;
  }

  /**
   * Dispatches command-line arguments to the tool via the {@code ToolRunner}.
   */
  public static void main(String[] args) throws Exception {
    ToolRunner.run(new BooleanRetrievalCompressed(), args);
  }
}

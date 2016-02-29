package node_count;

import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import node_count.build_corpus.RegexProjectSet;
import node_count.exceptions.PythonParsingException;
import node_count.exceptions.QuoteRuleException;
import node_count.metric.FeatureCount;
import node_count.metric.FeatureCountFactory;
import node_count.metric.FeatureDictionary;
import node_count.metric.FeatureSetClass;

import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.TreeIterator;

// refactoring tree node. It's one node from the diagram in the paper...
public class RTNode extends TreeSet<RegexProjectSet> {
	private static boolean verbose = true;
	private static int attemptCounter = 0;
	private static final char delim = '•';
	private static final long serialVersionUID = 6428904144599866954L;
	private final String name;
	private final FeatureSetClass requiredFeatures;
	private final Pattern filter;
	private static final Pattern CCC_WITH_RANGE = Pattern.compile("CHARACTER_CLASS•DOWN•(((.|\\\\0x..)|(\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W))•)*(RANGE•DOWN•(.|\\\\0x..)•(.|\\\\0x..)•UP•)(((.|\\\\0x..)|(\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W)|(RANGE•DOWN•(.|\\\\0x..)•(.|\\\\0x..)•UP))•)*UP•");
	private static final Pattern CCC_WITH_DEFAULT = Pattern.compile("CHARACTER_CLASS•DOWN•(((.|\\\\0x..)|(RANGE•DOWN•(.|\\\\0x..)•(.|\\\\0x..)•UP))•)*(\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W)•(((.|\\\\0x..)|(\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W)|(RANGE•DOWN•(.|\\\\0x..)•(.|\\\\0x..)•UP))•)*UP•");
	private static final Pattern CCC_WITH_DEFAULT_OR_RANGE = Pattern.compile("CHARACTER_CLASS•DOWN•(((\\\\0x..){1,2}|.)•)*((RANGE•DOWN•((\\\\0x..){1,2}|.)•((\\\\0x..){1,2}|.)•UP•)|(\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W)•)+(((\\\\0x..){1,2}|.|(\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W)|(RANGE•DOWN•((\\\\0x..){1,2}|.)•((\\\\0x..){1,2}|.)•UP))•)*UP•");
	private static final Pattern C3_ORofLITERALS_NO_DEFAULTS = Pattern.compile("OR•DOWN•(ALTERNATIVE•DOWN•ELEMENT•DOWN•(.|\\\\0x..)•UP•UP•)+UP•");
	private static final Pattern C6_ORofLITERALS_REQUIRING_DEFAULT = Pattern.compile("OR•DOWN•(ALTERNATIVE•DOWN•ELEMENT•DOWN•(.|\\\\0x..)•UP•UP•)*(ALTERNATIVE•DOWN•ELEMENT•DOWN•(\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W|ANY)•UP•UP•)+(ALTERNATIVE•DOWN•ELEMENT•DOWN•(.|\\\\0x..|\\\\d|\\\\D|\\\\s|\\\\S|\\\\w|\\\\W|ANY)•UP•UP•)*UP•");

	private static final Pattern S2_repeatingChars = Pattern.compile("(ELEMENT•DOWN•.•UP•)\\1+");
	private static final Pattern S2_repeatingElement = Pattern.compile("(ELEMENT•[^ ]+)\\1");

	private static final Pattern OR_WITHOUT_PREFIX_OR_SUFFIX = Pattern.compile("^OR•DOWN•");
	private static final Pattern OR_IN_CG_FIRST_ELEMENT = Pattern.compile("^ALTERNATIVE•DOWN•ELEMENT•DOWN•CAPTURING_GROUP•DOWN•OR•DOWN•");

	private static final Pattern R3_OR_REQUIRING_REDUNDANCY = Pattern.compile("OR•DOWN•(?:ALTERNATIVE•DOWN•(?:.+)UP•)*(ALTERNATIVE•DOWN•(?:.+)UP•)+(?:ALTERNATIVE•DOWN•(?:.+)UP•)*\\1");

	private static final Pattern P1_MATCHALLOR = Pattern.compile("OR•DOWN•ALTERNATIVE•DOWN•ELEMENT•DOWN•ANY•UP•UP•ALTERNATIVE•DOWN•ELEMENT•DOWN•\\\\0x0a•UP•UP•UP•|OR•DOWN•ALTERNATIVE•DOWN•ELEMENT•DOWN•\\\\0x0a•UP•UP•ALTERNATIVE•DOWN•ELEMENT•DOWN•ANY•UP•UP•UP•");
	private static final Pattern P2_S_OPTION = Pattern.compile("ELEMENT•DOWN•OPTIONS•DOWN•SET•DOWN•(.•)*s•(.•)*UP•UNSET•UP•UP•");
	private static final Pattern P3_M_OPTION = Pattern.compile("ELEMENT•DOWN•OPTIONS•DOWN•SET•DOWN•(.•)*m•(.•)*UP•UNSET•UP•UP•");
	private static final Pattern P5_I_OPTION = Pattern.compile("ELEMENT•DOWN•OPTIONS•DOWN•SET•DOWN•(.•)*i•(.•)*UP•UNSET•UP•UP•");

	private static final Pattern CCC_WRAPPED_ESCAPE_CHAR = Pattern.compile("(?<=\\[)([.^$*+?(){}\\\\|\\[])(?=\\])");
	private static final Pattern CCC_WRAPPED_NONESCAPE_CHAR = Pattern.compile("(?<=\\[)([^.^$*+?(){}\\\\|\\[])(?=\\])");
	private static final Pattern HEX_OR_OCTAL = Pattern.compile("(\\\\x[a-f0-9A-F]{2})|((\\\\0\\d*)|(\\\\\\d{3}))");

	private static final FeatureSetClass rangeFSC = new FeatureSetClass(FeatureCountFactory.constructWithOneOfEach(FeatureDictionary.I_CC_RANGE));
	private static final FeatureSetClass defaultsFSC = new FeatureSetClass(FeatureCountFactory.constructWithOneOfEach(FeatureDictionary.I_CC_DECIMAL, FeatureDictionary.I_CC_NDECIMAL, FeatureDictionary.I_CC_NWHITESPACE, FeatureDictionary.I_CC_NWORD, FeatureDictionary.I_CC_WHITESPACE, FeatureDictionary.I_CC_WORD));

	public RTNode(String name, FeatureSetClass requiredFeatures, Pattern filter) {
		super();
		this.filter = filter;
		this.name = name;
		this.requiredFeatures = requiredFeatures;
	}

	public String getName() {
		return name;
	}

	public FeatureSetClass getRequiredFeatures() {
		return requiredFeatures;
	}

	public Pattern getFilter() {
		return filter;
	}

	public String getContent() {
		StringBuilder sb = new StringBuilder();
		for (RegexProjectSet rps : this) {
			sb.append(rps.getContent()+"\t"+rps.getProjectsCSV()+"\n");
		}
		return sb.toString();
	}

	// /////////matching logic for all////////

	public boolean addIfMatches(RegexProjectSet regex,StringBuilder errorLogContent)
			throws InterruptedException, ExecutionException {
		if (verbose && attemptCounter++ % 500 == 0) {
			System.out.println("counter: " + attemptCounter);
		}

		if (regex.subsumes(requiredFeatures)) {
			Matcher m = filter.matcher(regex.unquoted);
			if (m.find()) {
				switch (name) {
				case C.D1:
				case C.D2:
				case C.D3:
				case C.D4:

				case C.S1:
					return this.add(regex);
				case C.S2:
					return matchesS2(regex) ? this.add(regex) : false;
				case C.S3:

				case C.L1:
				case C.L2:
				case C.L3:
				case C.L4:

				case C.C1:
					return this.add(regex);
				case C.C2:
					return matchesC2(regex) ? this.add(regex) : false;
				case C.C3:
					return matchesC3(regex) ? this.add(regex) : false;
				case C.C4:
					return this.add(regex);
				case C.C5:
					return matchesC5(regex) ? this.add(regex) : false;
				case C.C6:
					return matchesC6(regex) ? this.add(regex) : false;

				case C.R1:
					return matchesR1(regex) ? this.add(regex) : false;
				case C.R2:
					return matchesR2(regex) ? this.add(regex) : false;
				case C.R3:
					return matchesR3(regex,errorLogContent) ? this.add(regex) : false;

				case C.P1:
					return matchesP1(regex) ? this.add(regex) : false;
				case C.P2:
					return matchesP2(regex) ? this.add(regex) : false;
				case C.P3:
					return matchesP3(regex) ? this.add(regex) : false;
				case C.P4:
					return matchesP4(regex) ? this.add(regex) : false;
				case C.P5:
					return matchesP5(regex) ? this.add(regex) : false;
				case C.P6:
					return matchesP6(regex) ? this.add(regex) : false;

				case C.T1:
					return matchesT1(regex) ? this.add(regex) : false;
				case C.T2:
				case C.T3:
					return this.add(regex);
				case C.T4:
					return matchesT4(regex) ? this.add(regex) : false;
				case C.T5:
					return matchesT5(regex) ? this.add(regex) : false;
				case C.T6:
				case C.T7:

				case C.W1:
				case C.W2:
				case C.W3:
				case C.W4:

				case C.B1:
				case C.B2:
				default:
					return this.add(regex);
				}
			}
		}
		return false;
	}

	private boolean matchesS2(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		if (true) {
			Matcher m1 = S2_repeatingChars.matcher(tokenStream);
			String charPairsRemoved = m1.replaceAll("$1");
			Matcher m = S2_repeatingElement.matcher(charPairsRemoved);
			return m.find();
		} else {
			Matcher m = S2_repeatingElement.matcher(tokenStream);
			return m.find();
		}

	}

	private static boolean matchesC2(RegexProjectSet regex) {
		// C2 is any CCC that has no ranges or defaults,
		// and we know it has a CCC
		String tokenStream = getTokenStream(regex);

		// this matcher REQUIRES there to be a CCC containing at least one range
		// or default
		Matcher m1 = CCC_WITH_DEFAULT_OR_RANGE.matcher(tokenStream);

		// so if none can be found, than all CCCs present don't have these
		// features, as needed for C2
		return !m1.find();
	}

	private static boolean matchesC3(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = C3_ORofLITERALS_NO_DEFAULTS.matcher(tokenStream);
		return (m1.find());
	}

	private static boolean matchesC5(RegexProjectSet regex) {
		// C5 is any CCC that has defaults, but no ranges
		// and we know it has a CCC
		String tokenStream = getTokenStream(regex);

		// this matcher REQUIRES there to be a CCC containing at least one range
		Matcher m1 = CCC_WITH_RANGE.matcher(tokenStream);
		// this matcher REQUIRES there to be a CCC containing at least one
		// default
		Matcher m2 = CCC_WITH_DEFAULT.matcher(tokenStream);

		// so if no CCC with a range can be found and we can find a CCC with a
		// default, it matches C5
		return !m1.find() && m2.find();
	}

	private static boolean matchesC6(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = C6_ORofLITERALS_REQUIRING_DEFAULT.matcher(tokenStream);
		return (m1.find());
	}
	
	private static boolean matchesR1(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = OR_WITHOUT_PREFIX_OR_SUFFIX.matcher(tokenStream);
		return (!m1.find() && !isCG_OR(regex,tokenStream));
	}
	
	private static boolean matchesR2(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = OR_WITHOUT_PREFIX_OR_SUFFIX.matcher(tokenStream);		
		return (m1.find() || isCG_OR(regex,tokenStream));
	}

	private static boolean matchesR3(RegexProjectSet regex, StringBuilder errorLogContent)
			throws InterruptedException, ExecutionException {
		
		ExecutorService exec = Executors.newSingleThreadExecutor();
		final String tokenStream = getTokenStream(regex);
		FindPatternTask findR3Task = new FindPatternTask(R3_OR_REQUIRING_REDUNDANCY,tokenStream);
		Future<Boolean> findFuture = exec.submit(findR3Task);
		double backoffCounter = 0.0;
		int maxCounter = 9;
		int wait =0;
		while(backoffCounter++ < maxCounter && !findFuture.isDone()){
			wait = (int) Math.pow(2.0, backoffCounter) + 10;
			Thread.sleep(wait);
		}
		if (backoffCounter > 6) {
			errorLogContent.append("R3: onery regex: "+regex.unquoted);
		}
		try {
            return findFuture.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false;
        }finally{
        	exec.shutdownNow();
        }
	}

	private static boolean matchesP1(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		// System.out.println(tokenStream);
		Matcher m1 = P1_MATCHALLOR.matcher(tokenStream);
		return (m1.find());
	}

	private static boolean matchesP2(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = P2_S_OPTION.matcher(tokenStream);
		return (m1.find());
	}

	private static boolean matchesP3(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = P3_M_OPTION.matcher(tokenStream);
		return (m1.find());
	}

	// uses P3_M_OPTION also, but the RTNode regex filter is different
	private static boolean matchesP4(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = P3_M_OPTION.matcher(tokenStream);
		return (m1.find());
	}

	private static boolean matchesP5(RegexProjectSet regex) {
		String tokenStream = getTokenStream(regex);
		Matcher m1 = P5_I_OPTION.matcher(tokenStream);
		return (m1.find());
	}

	private static boolean matchesP6(RegexProjectSet regex) {
		// true iff ignorecase makes no effect
		return false;
	}

	private static boolean matchesT1(RegexProjectSet regex) {
		Matcher m1 = CCC_WRAPPED_ESCAPE_CHAR.matcher(regex.unquoted);
		Matcher m2 = HEX_OR_OCTAL.matcher(regex.unquoted);
		return !m1.find() && !m2.find();
	}

	private static boolean matchesT4(RegexProjectSet regex) {
		Matcher m1 = CCC_WRAPPED_ESCAPE_CHAR.matcher(regex.unquoted);
		Matcher m2 = CCC_WRAPPED_NONESCAPE_CHAR.matcher(regex.unquoted);
		return m1.find() && !m2.find();
	}

	private static boolean matchesT5(RegexProjectSet regex) {
		Matcher m1 = CCC_WRAPPED_ESCAPE_CHAR.matcher(regex.unquoted);
		Matcher m2 = CCC_WRAPPED_NONESCAPE_CHAR.matcher(regex.unquoted);
		return m1.find() && m2.find();
	}

	// //////////helpers////////////
	
	private static boolean isCG_OR(RegexProjectSet regex,String tokenStream){
		Matcher m1 = OR_IN_CG_FIRST_ELEMENT.matcher(tokenStream);
		//expecting something like (a|b) which translates to:
		//ALTERNATIVE•DOWN•ELEMENT•DOWN•CAPTURING_GROUP•DOWN•OR•DOWN
		//and so the CG is the only ELEMENT child of the root node: ALTERNATIVE
		return m1.find() && regex.getRootTree().getChildCount()==1;

	}

	// cite:
	// http://stackoverflow.com/questions/7487917/convert-byte-array-to-escaped-string
	private static String escape(byte[] data) {
		StringBuilder cbuf = new StringBuilder();
		for (byte b : data) {
			if (b >= 0x20 && b <= 0x7e) {
				cbuf.append((char) b);
			} else {
				cbuf.append(String.format("\\0x%02x", b & 0xFF));
			}
		}
		return cbuf.toString();
	}

	private static String getTokenStream(RegexProjectSet regex) {
		TreeIterator it = new TreeIterator(regex.getRootTree());
		StringBuilder sb = new StringBuilder();
		while (it.hasNext()) {
			sb.append(escape(((CommonTree) it.next()).getText().getBytes()) +
				delim);
		}
		return sb.toString();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		RTNode other = (RTNode) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}

	public static void main(String[] args) throws IllegalArgumentException,
			QuoteRuleException, PythonParsingException {
		// testing some ideas
		TreeSet<Integer> testPIDs = new TreeSet<Integer>();
		testPIDs.add(1);
		RegexProjectSet testRegex = new RegexProjectSet("'M|N|a|(b(b|c))'", testPIDs);
		System.out.println(matchesR2(testRegex));
	}
}

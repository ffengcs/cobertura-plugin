package hudson.plugins.cobertura;

import hudson.plugins.cobertura.targets.CoverageResult;
import hudson.plugins.cobertura.targets.CoverageElement;
import hudson.plugins.cobertura.targets.CoverageMetric;
import org.xml.sax.SAXException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.util.Stack;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Created by IntelliJ IDEA.
 *
 * @author connollys
 * @since 03-Jul-2007 09:03:30
 */
public class CoberturaCoverageParser {

    /**
     * Do not instantiate CoberturaCoverageParser.
     */
    private CoberturaCoverageParser() {
    }

    public static CoverageResult parse(File inFile, String pathPrefix) throws IOException {
        FileInputStream fileInputStream = null;
        BufferedInputStream bufferedInputStream = null;
        try {
            fileInputStream = new FileInputStream(inFile);
            bufferedInputStream = new BufferedInputStream(fileInputStream);
            CoberturaCoverageParser parser = new CoberturaCoverageParser();
            return parse(bufferedInputStream);
        } finally {
            try {
                if (bufferedInputStream != null)
                    bufferedInputStream.close();
                if (fileInputStream != null)
                    fileInputStream.close();
            } catch (IOException e) {
            }
        }
    }

    public static CoverageResult parse(InputStream in) throws IOException {
        if (in == null) throw new NullPointerException();
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setValidating(false);
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
        } catch (SAXNotRecognizedException e) {
        } catch (SAXNotSupportedException e) {
        }
        try {
            SAXParser parser = factory.newSAXParser();
            CoberturaXmlHandler handler = new CoberturaXmlHandler();
            parser.parse(in, handler);
            return handler.getRootCoverage();
        } catch (ParserConfigurationException e) {
            throw new IOException("Cannot parse coverage results", e);
        } catch (SAXException e) {
            throw new IOException("Cannot parse coverage results", e);
        }
    }
}

class CoberturaXmlHandler extends DefaultHandler {
    private CoverageResult rootCoverage = null;
    private Stack<CoverageResult> stack = new Stack<CoverageResult>();
    private static final String DEFAULT_PACKAGE = "<default>";

    public void startDocument() throws SAXException {
        super.startDocument();
        rootCoverage = new CoverageResult(CoverageElement.PROJECT, null, "Cobertura Coverage Report");
        stack.clear();
    }

    public void endDocument() throws SAXException {
        if (!stack.empty()) {
            throw new SAXException("Unbalanced parse of cobertua coverage results.");
        }
        super.endDocument();    //To change body of overridden methods use File | Settings | File Templates.
    }

    private void descend(CoverageElement childType, String childName) {
        CoverageResult child = rootCoverage.getChild(childName);
        stack.push(rootCoverage);
        if (child == null) {
            rootCoverage = new CoverageResult(childType, rootCoverage, childName);
        } else {
            rootCoverage = child;
        }
    }

    private void ascend(CoverageElement element) {
        while (rootCoverage != null && rootCoverage.getElement() != element) {
            rootCoverage = stack.pop();
        }
        if (rootCoverage != null) {
            rootCoverage = stack.pop();
        }
    }

    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        super.startElement(uri, localName, qName, attributes);
        String name = attributes.getValue("name");
        if ("coverage".equals(qName)) {
        } else if ("package".equals(qName)) {
            if ("".equals(name) || null == name) {
                name = DEFAULT_PACKAGE;
            }
            descend(CoverageElement.JAVA_PACKAGE, name);
        } else if ("class".equals(qName)) {
            assert rootCoverage.getElement() == CoverageElement.JAVA_PACKAGE;
            // cobertura combines file and class
            String filename = attributes.getValue("filename").replace('\\', '/');
            String packageName = rootCoverage.getName();
            if (!DEFAULT_PACKAGE.equals(packageName)) {
                if (filename.startsWith(packageName.replace('.', '/') + "/")) {
                    filename = filename.substring(packageName.length() + 1);
                }
            }
            if (name.startsWith(packageName + ".")) {
                name = name.substring(packageName.length() + 1);
            }
            descend(CoverageElement.JAVA_FILE, filename);
            descend(CoverageElement.JAVA_CLASS, name);
        } else if ("method".equals(qName)) {
            String methodName = buildMethodName(name, attributes.getValue("signature"));
            descend(CoverageElement.JAVA_METHOD, methodName);
        } else if ("line".equals(qName)) {
            String hitsString = attributes.getValue("hits");
            try {
                int hits = Integer.parseInt(hitsString);
                rootCoverage.updateMetric(CoverageMetric.LINE, Ratio.create((hits == 0) ? 0 : 1, 1));
            } catch (NumberFormatException e) {
                // ignore
            }
            if (Boolean.parseBoolean(attributes.getValue("branch"))) {
                final String conditionCoverage = attributes.getValue("condition-coverage");
                if (conditionCoverage != null) {
                    // some cases in the wild have branch = true but no condition-coverage attribute

                    // should be of the format xxx% (yyy/zzz)
                    Matcher matcher = Pattern.compile("(\\d*)\\%\\s*\\((\\d*)/(\\d*)\\)").matcher(conditionCoverage);
                    if (matcher.matches()) {
                        assert matcher.groupCount() == 3;
                        final String numeratorStr = matcher.group(2);
                        final String denominatorStr = matcher.group(3);
                        try {
                            rootCoverage.updateMetric(CoverageMetric.CONDITIONAL,
                                    Ratio.create(Integer.parseInt(numeratorStr), Integer.parseInt(denominatorStr)));
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                }
            }
        }

    }

    private String buildMethodName(String name, String signature) {
        Matcher signatureMatcher = Pattern.compile("\\((.*)\\)(.*)").matcher(signature);
        StringBuilder methodName = new StringBuilder();
        if (signatureMatcher.matches()) {
            Pattern argMatcher = Pattern.compile("\\[*([TL][^\\;]*\\;)|([ZCBSIFJDV])");
            String returnType = signatureMatcher.group(2);
            Matcher matcher = argMatcher.matcher(returnType);
            if (matcher.matches()) {
                methodName.append(parseMethodArg(matcher.group()));
                methodName.append(' ');
            }
            methodName.append(name);
            String args = signatureMatcher.group(1);
            matcher = argMatcher.matcher(args);
            methodName.append('(');
            boolean first = true;
            while (matcher.find()) {
                if (!first) {
                    methodName.append(',');
                }
                methodName.append(parseMethodArg(matcher.group()));
                first = false;
            }
            methodName.append(')');
        } else {
            methodName.append(name);
        }
        return methodName.toString();
    }

    private String parseMethodArg(String s) {
        char c = s.charAt(0);
        int end;
        switch (c) {
            case'Z':
                return "boolean";
            case'C':
                return "char";
            case'B':
                return "byte";
            case'S':
                return "short";
            case'I':
                return "int";
            case'F':
                return "float";
            case'J':
                return "";
            case'D':
                return "double";
            case'V':
                return "void";
            case'[':
                return parseMethodArg(s.substring(1)) + "[]";
            case'T':
            case'L':
                end = s.indexOf(';');
                return s.substring(1, end).replace('/', '.');
        }
        return s;
    }

    public void endElement(String uri, String localName, String qName) throws SAXException {
        if ("coverage".equals(qName)) {
        } else if ("package".equals(qName)) {
            ascend(CoverageElement.JAVA_PACKAGE);
        } else if ("class".equals(qName)) {
            ascend(CoverageElement.JAVA_CLASS);
            ascend(CoverageElement.JAVA_FILE);
        } else if ("method".equals(qName)) {
            ascend(CoverageElement.JAVA_METHOD);
        }
        super.endElement(uri, localName, qName);    //To change body of overridden methods use File | Settings | File Templates.
    }

    public CoverageResult getRootCoverage() {
        return rootCoverage;
    }
}

class CoberturaXmlHandlerStackItem {
    private CoverageResult metric;
    private CoberturaCoverageTotals totals;


    public CoberturaXmlHandlerStackItem(CoverageResult metric) {
        this.metric = metric;
        totals = new CoberturaCoverageTotals();
    }

    public CoverageResult getMetric() {
        return metric;
    }

    public CoberturaCoverageTotals getTotals() {
        return totals;
    }
}

class CoberturaCoverageTotals {
    private long totalLineCount;
    private long coverLineCount;
    private long totalConditionCount;
    private long coverConditionCount;
    private long totalMethodCount;
    private long coverMethodCount;

    public CoberturaCoverageTotals() {
        totalLineCount = 0;
        coverLineCount = 0;
        totalConditionCount = 0;
        coverConditionCount = 0;
        totalMethodCount = 0;
        coverMethodCount = 0;
    }

    public void addLine(boolean covered) {
        totalLineCount++;
        if (covered) {
            coverLineCount++;
        }
    }

    public void addMethod(boolean covered) {
        totalMethodCount++;
        if (covered) {
            coverMethodCount++;
        }
    }

    public void addLine(boolean covered, int condCoverCount, int condTotalCount) {
        addLine(covered);
        totalConditionCount += condTotalCount;
        coverConditionCount += condCoverCount;
    }

    public void addTotal(CoberturaCoverageTotals sub) {
        totalLineCount += sub.totalLineCount;
        coverLineCount += sub.coverLineCount;
        totalConditionCount += sub.totalConditionCount;
        coverConditionCount += sub.coverConditionCount;
        totalMethodCount += sub.totalMethodCount;
        coverMethodCount += sub.coverMethodCount;
    }

    public Ratio getLineCoverage() {
        return Ratio.create(coverLineCount, totalLineCount);
    }

    public Ratio getConditionalCoverage() {
        return Ratio.create(coverLineCount, totalLineCount);
    }

    public Ratio getMethodCoverage() {
        return Ratio.create(coverMethodCount, totalMethodCount);
    }
}
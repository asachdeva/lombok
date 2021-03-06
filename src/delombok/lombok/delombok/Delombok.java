/*
 * Copyright (C) 2009-2010 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.delombok;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

import lombok.javac.CommentCatcher;
import lombok.javac.LombokOptions;

import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.main.OptionName;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.zwitserloot.cmdreader.CmdReader;
import com.zwitserloot.cmdreader.Description;
import com.zwitserloot.cmdreader.Excludes;
import com.zwitserloot.cmdreader.InvalidCommandLineException;
import com.zwitserloot.cmdreader.Mandatory;
import com.zwitserloot.cmdreader.Sequential;
import com.zwitserloot.cmdreader.Shorthand;

public class Delombok {
	private Charset charset = Charset.defaultCharset();
	private Context context = new Context();
	private Writer presetWriter;
	
	public void setWriter(Writer writer) {
		this.presetWriter = writer;
	}
	
	public Delombok() {
//		context.put(DeleteLombokAnnotations.class, new DeleteLombokAnnotations(true));
	}
	
	private PrintStream feedback = System.err;
	private boolean verbose;
	private boolean noCopy;
	private boolean force = false;
	private String classpath, sourcepath;
	private LinkedHashMap<File, File> fileToBase = new LinkedHashMap<File, File>();
	private List<File> filesToParse = new ArrayList<File>();
	
	/** If null, output to standard out. */
	private File output = null;
	
	private static class CmdArgs {
		@Shorthand("v")
		@Description("Print the name of each file as it is being delombok-ed.")
		@Excludes("quiet")
		private boolean verbose;
		
		@Shorthand("q")
		@Description("No warnings or errors will be emitted to standard error")
		@Excludes("verbose")
		private boolean quiet;
		
		@Shorthand("e")
		@Description("Sets the encoding of your source files. Defaults to the system default charset. Example: \"UTF-8\"")
		private String encoding;
		
		@Shorthand("p")
		@Description("Print delombok-ed code to standard output instead of saving it in target directory")
		private boolean print;
		
		@Shorthand("d")
		@Description("Directory to save delomboked files to")
		@Mandatory(onlyIfNot={"print", "help"})
		private String target;
		
		@Shorthand("c")
		@Description("Classpath (analogous to javac -cp option)")
		private String classpath;
		
		@Shorthand("s")
		@Description("Sourcepath (analogous to javac -sourcepath option)")
		private String sourcepath;
		
		@Description("Files to delombok. Provide either a file, or a directory. If you use a directory, all files in it (recursive) are delombok-ed")
		@Sequential
		private List<String> input = new ArrayList<String>();
		
		@Description("Lombok will only delombok source files. Without this option, non-java, non-class files are copied to the target directory.")
		@Shorthand("n")
		private boolean nocopy;
		
		private boolean help;
	}
	
	public static void main(String[] rawArgs) {
		CmdReader<CmdArgs> reader = CmdReader.of(CmdArgs.class);
		CmdArgs args;
		try {
			args = reader.make(rawArgs);
		} catch (InvalidCommandLineException e) {
			System.err.println("ERROR: " + e.getMessage());
			System.err.println(reader.generateCommandLineHelp("delombok"));
			System.exit(1);
			return;
		}
		
		if (args.help || args.input.isEmpty()) {
			if (!args.help) System.err.println("ERROR: no files or directories to delombok specified.");
			System.err.println(reader.generateCommandLineHelp("delombok"));
			System.exit(args.help ? 0 : 1);
			return;
		}
		
		Delombok delombok = new Delombok();
		
		if (args.quiet) delombok.setFeedback(new PrintStream(new OutputStream() {
			@Override public void write(int b) throws IOException {
				//dummy - do nothing.
			}
		}));
		
		if (args.encoding != null) {
			try {
				delombok.setCharset(args.encoding);
			} catch (UnsupportedCharsetException e) {
				System.err.println("ERROR: Not a known charset: " + args.encoding);
				System.exit(1);
				return;
			}
		}
		
		if (args.verbose) delombok.setVerbose(true);
		if (args.nocopy) delombok.setNoCopy(true);
		if (args.print) {
			delombok.setOutputToStandardOut();
		} else {
			delombok.setOutput(new File(args.target));
		}
		
		if (args.classpath != null) delombok.setClasspath(args.classpath);
		if (args.sourcepath != null) delombok.setSourcepath(args.sourcepath);
		
		try {
			for (String in : args.input) {
				File f = new File(in).getAbsoluteFile();
				if (f.isFile()) {
					delombok.addFile(f.getParentFile(), f.getName());
				} else if (f.isDirectory()) {
					delombok.addDirectory(f);
				} else if (!f.exists()) {
					if (!args.quiet) System.err.println("WARNING: does not exist - skipping: " + f);
				} else {
					if (!args.quiet) System.err.println("WARNING: not a standard file or directory - skipping: " + f);
				}
			}
			
			delombok.delombok();
		} catch (Exception e) {
			if (!args.quiet) {
				String msg = e.getMessage();
				if (msg != null && msg.startsWith("DELOMBOK: ")) System.err.println(msg.substring("DELOMBOK: ".length()));
				else {
					e.printStackTrace();
				}
				System.exit(1);
				return;
			}
		}
	}
	
	public void setCharset(String charsetName) throws UnsupportedCharsetException {
		if (charsetName == null) {
			charset = Charset.defaultCharset();
			return;
		}
		charset = Charset.forName(charsetName);
	}
	
	public void setDiagnosticsListener(DiagnosticListener<JavaFileObject> diagnostics) {
		if (diagnostics != null) context.put(DiagnosticListener.class, diagnostics);
	}
	
	public void setForceProcess(boolean force) {
		this.force = force;
	}
	
	public void setFeedback(PrintStream feedback) {
		this.feedback = feedback;
	}
	
	public void setClasspath(String classpath) {
		this.classpath = classpath;
	}
	
	public void setSourcepath(String sourcepath) {
		this.sourcepath = sourcepath;
	}
	
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}
	
	public void setNoCopy(boolean noCopy) {
		this.noCopy = noCopy;
	}
	
	public void setOutput(File dir) {
		if (dir.isFile() || (!dir.isDirectory() && dir.getName().endsWith(".java"))) throw new IllegalArgumentException(
				"DELOMBOK: delombok will only write to a directory. " +
				"If you want to delombok a single file, use -p to output to standard output, then redirect this to a file:\n" +
				"delombok MyJavaFile.java -p >MyJavaFileDelombok.java");
		output = dir;
	}
	
	public void setOutputToStandardOut() {
		this.output = null;
	}
	
	public void addDirectory(File base) throws IOException {
		addDirectory0(false, base, "", 0);
	}
	
	public void addDirectory1(boolean copy, File base, String name) throws IOException {
		File f = new File(base, name);
		if (f.isFile()) {
			String extension = getExtension(f);
			if (extension.equals("java")) addFile(base, name);
			else if (extension.equals("class")) skipClass(name);
			else copy(copy, base, name);
		} else if (!f.exists()) {
			feedback.printf("Skipping %s because it does not exist.\n", canonical(f));
		} else if (!f.isDirectory()) {
			feedback.printf("Skipping %s because it is a special file type.\n", canonical(f));
		}
	}
	
	private void addDirectory0(boolean inHiddenDir, File base, String suffix, int loop) throws IOException {
		File dir = suffix.isEmpty() ? base : new File(base, suffix);
		
		if (dir.isDirectory()) {
			boolean thisDirIsHidden = !inHiddenDir && new File(canonical(dir)).getName().startsWith(".");
			if (loop >= 100) {
				feedback.printf("Over 100 subdirectories? I'm guessing there's a loop in your directory structure. Skipping: %s\n", suffix);
			} else {
				File[] list = dir.listFiles();
				if (list.length > 0) {
					if (thisDirIsHidden && !noCopy && output != null) {
						feedback.printf("Only processing java files (not copying non-java files) in %s because it's a hidden directory.\n", canonical(dir));
					}
					for (File f : list) {
						addDirectory0(inHiddenDir || thisDirIsHidden, base, suffix + (suffix.isEmpty() ? "" : File.separator) + f.getName(), loop + 1);
					}
				} else {
					if (!thisDirIsHidden && !noCopy && !inHiddenDir && output != null && !suffix.isEmpty()) {
						File emptyDir = new File(output, suffix);
						emptyDir.mkdirs();
						if (verbose) feedback.printf("Creating empty directory: %s\n", canonical(emptyDir));
					}
				}
			}
		} else {
			addDirectory1(!inHiddenDir && !noCopy, base, suffix);
		}
	}
	
	private void skipClass(String fileName) {
		if (verbose) feedback.printf("Skipping class file: %s\n", fileName);
	}
	
	private void copy(boolean copy, File base, String fileName) throws IOException {
		if (output == null) {
			feedback.printf("Skipping resource file: %s\n", fileName);
			return;
		}
		
		if (!copy) {
			if (verbose) feedback.printf("Skipping resource file: %s\n", fileName);
			return;
		}
		
		if (verbose) feedback.printf("Copying resource file: %s\n", fileName);
		byte[] b = new byte[65536];
		File inFile = new File(base, fileName);
		FileInputStream in = new FileInputStream(inFile);
		try {
			File outFile = new File(output, fileName);
			outFile.getParentFile().mkdirs();
			FileOutputStream out = new FileOutputStream(outFile);
			try {
				while (true) {
					int r = in.read(b);
					if (r == -1) break;
					out.write(b, 0, r);
				}
			} finally {
				out.close();
			}
		} finally {
			in.close();
		}
	}
	
	public void addFile(File base, String fileName) throws IOException {
		if (output != null && canonical(base).equals(canonical(output))) throw new IOException(
				"DELOMBOK: Output file and input file refer to the same filesystem location. Specify a separate path for output.");
		
		File f = new File(base, fileName);
		filesToParse.add(f);
		fileToBase.put(f, base);
	}
	
	private static <T> com.sun.tools.javac.util.List<T> toJavacList(List<T> list) {
		com.sun.tools.javac.util.List<T> out = com.sun.tools.javac.util.List.nil();
		ListIterator<T> li = list.listIterator(list.size());
		while (li.hasPrevious()) out = out.prepend(li.previous());
		return out;
	}
	
	public boolean delombok() throws IOException {
		LombokOptions options = LombokOptions.replaceWithDelombokOptions(context);
		options.put(OptionName.ENCODING, charset.name());
		if (classpath != null) options.put(OptionName.CLASSPATH, classpath);
		if (sourcepath != null) options.put(OptionName.SOURCEPATH, sourcepath);
		options.put("compilePolicy", "attr");
		
		CommentCatcher catcher = CommentCatcher.create(context);
		JavaCompiler compiler = catcher.getCompiler();
		
		List<JCCompilationUnit> roots = new ArrayList<JCCompilationUnit>();
		Map<JCCompilationUnit, File> baseMap = new IdentityHashMap<JCCompilationUnit, File>();
		
		
		compiler.initProcessAnnotations(Collections.singleton(new lombok.javac.apt.Processor()));
		
		for (File fileToParse : filesToParse) {
			
			@SuppressWarnings("deprecation")
			JCCompilationUnit unit = compiler.parse(fileToParse.getAbsolutePath());
			
			baseMap.put(unit, fileToBase.get(fileToParse));
			roots.add(unit);
		}
		
		if (compiler.errorCount() > 0) {
			// At least one parse error. No point continuing (a real javac run doesn't either).
			return false;
		}
		
		JavaCompiler delegate = compiler.processAnnotations(compiler.enterTrees(toJavacList(roots)));
		delegate.flow(delegate.attribute(delegate.todo));
		for (JCCompilationUnit unit : roots) {
			DelombokResult result = new DelombokResult(catcher.getComments(unit), unit, force || options.isChanged(unit));
			if (verbose) feedback.printf("File: %s [%s]\n", unit.sourcefile.getName(), result.isChanged() ? "delomboked" : "unchanged");
			Writer rawWriter;
			if (presetWriter != null) rawWriter = presetWriter;
			else if (output == null) rawWriter = createStandardOutWriter();
			else rawWriter = createFileWriter(output, baseMap.get(unit), unit.sourcefile.toUri());
			BufferedWriter writer = new BufferedWriter(rawWriter);
			try {
				result.print(writer);
			} finally {
				if (output != null) {
					writer.close();
				} else {
					writer.flush();
				}
			}
		}
		delegate.close();
		
		return true;
	}
	
	private static String canonical(File dir) {
		try {
			return dir.getCanonicalPath();
		} catch (Exception e) {
			return dir.getAbsolutePath();
		}
	}
	
	private static String getExtension(File dir) {
		String name = dir.getName();
		int idx = name.lastIndexOf('.');
		return idx == -1 ? "" : name.substring(idx+1);
	}
	
	private Writer createFileWriter(File outBase, File inBase, URI file) throws IOException {
		URI base = inBase.toURI();
		URI relative = base.relativize(base.resolve(file));
		File outFile;
		if (relative.isAbsolute()) {
			outFile = new File(outBase, new File(relative).getName());
		} else {
			outFile = new File(outBase, relative.getPath());
		}
		
		outFile.getParentFile().mkdirs();
		FileOutputStream out = new FileOutputStream(outFile);
		return createUnicodeEscapeWriter(out);
	}
	
	private Writer createStandardOutWriter() {
		return createUnicodeEscapeWriter(System.out);
	}
	
	private Writer createUnicodeEscapeWriter(OutputStream out) {
		return new UnicodeEscapeWriter(new OutputStreamWriter(out, charset), charset);
	}
}

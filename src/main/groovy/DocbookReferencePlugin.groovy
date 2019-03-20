/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.File
import java.util.zip.*

import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.*
import javax.xml.transform.Transformer
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

import org.apache.fop.apps.*
import org.apache.xml.resolver.CatalogManager
import org.apache.xml.resolver.tools.CatalogResolver
import org.gradle.api.*
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.*
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource
import org.xml.sax.XMLReader

import com.icl.saxon.TransformerFactoryImpl

class DocbookReferencePlugin implements Plugin<Project> {

	def void apply(Project project) {

		project.plugins.apply('base') // for `clean` task

		def tasks = project.tasks

		def multi = tasks.create("referenceHtmlMulti", HtmlMultiDocbookReferenceTask)
		def single = tasks.create("referenceHtmlSingle", HtmlSingleDocbookReferenceTask)
		def pdf = tasks.create("referencePdf", PdfDocbookReferenceTask)
		def epub = tasks.create("referenceEpub", EpubDocbookReferenceTask)

		def reference = tasks.create("reference") {
			group = 'Documentation'
			description = "Generates HTML and PDF reference documentation."
			dependsOn([multi, single, pdf, epub])

			ext.sourceDir = null // e.g. new File('src/reference')
			ext.outputDir = new File(project.buildDir, "reference")
			ext.pdfFilename = "${project.rootProject.name}-reference.pdf"
			ext.epubFilename = "${project.rootProject.name}-reference.epub"
			ext.sourceFileName = 'index.xml'
			ext.expandPlaceholders = '**/index.xml'
			ext.fopUserConfig = null
			ext.retainFo = false
			outputs.dir outputDir
		}

		project.gradle.taskGraph.whenReady {
			if (multi.sourceDir == null) multi.sourceDir = reference.sourceDir
			if (single.sourceDir == null) single.sourceDir = reference.sourceDir
			if (pdf.sourceDir == null) pdf.sourceDir = reference.sourceDir
			if (epub.sourceDir == null) epub.sourceDir = reference.sourceDir

			if (multi.outputDir == null) multi.outputDir = reference.outputDir
			if (single.outputDir == null) single.outputDir = reference.outputDir
			if (pdf.outputDir == null) pdf.outputDir = reference.outputDir
			if (epub.outputDir == null) epub.outputDir = reference.outputDir

			if (multi.sourceFileName == null) multi.sourceFileName = reference.sourceFileName
			if (single.sourceFileName == null) single.sourceFileName = reference.sourceFileName
			if (pdf.sourceFileName == null) pdf.sourceFileName = reference.sourceFileName
			if (epub.sourceFileName == null) epub.sourceFileName = reference.sourceFileName
		}

	}

}

abstract class AbstractDocbookReferenceTask extends DefaultTask {

	@InputDirectory
	File sourceDir // e.g. 'src/reference'

	@Input
	String sourceFileName

	String stylesheet

	String xdir

	@OutputDirectory
	File outputDir = new File(project.getBuildDir(), "reference")

	@TaskAction
	public final void transform() {
		// the docbook tasks issue spurious content to the console. redirect to INFO level
		// so it doesn't show up in the default log level of LIFECYCLE unless the user has
		// run gradle with '-d' or '-i' switches -- in that case show them everything
		switch (project.gradle.startParameter.logLevel) {
			case LogLevel.DEBUG:
			case LogLevel.INFO:
				break
			default:
				logging.captureStandardOutput(LogLevel.INFO)
				logging.captureStandardError(LogLevel.INFO)
		}

		// TODO call only once
		unpack()
		sourceDir = filterDocbookSources(sourceDir)

		SAXParserFactory factory = new org.apache.xerces.jaxp.SAXParserFactoryImpl()
		factory.setXIncludeAware(true)
		outputDir.mkdirs()

		File srcFile = new File(sourceDir, sourceFileName)
		String outputFilename = srcFile.getName().substring(0, srcFile.getName().length() - 4) + '.' + this.getExtension()

		File oDir = new File(outputDir, xdir)
		File outputFile = new File(oDir, outputFilename)

		Result result = new StreamResult(outputFile.getAbsolutePath())
		CatalogResolver resolver = new CatalogResolver(createCatalogManager())
		InputSource inputSource = new InputSource(srcFile.getAbsolutePath())

		XMLReader reader = factory.newSAXParser().getXMLReader()
		reader.setEntityResolver(resolver)
		TransformerFactory transformerFactory = new TransformerFactoryImpl()
		transformerFactory.setURIResolver(resolver)

		def File stylesheetFile = new File(new File(sourceDir, "xsl"), stylesheet)
		if(!stylesheetFile.exists()) {
			stylesheetFile = new File("${project.buildDir}/docbook-resources/xsl/${stylesheet}")
		}
		URL url = stylesheetFile.toURI().toURL()
		Source source = new StreamSource(url.openStream(), url.toExternalForm())
		Transformer transformer = transformerFactory.newTransformer(source)

		transformer.setParameter("highlight.source", "1")
		transformer.setParameter("highlight.xslthl.config", new File("${project.buildDir}/docbook-resources/xsl", "xslthl-config.xml").toURI().toURL())

		preTransform(transformer, srcFile, outputFile)

		transformer.transform(new SAXSource(reader, inputSource), result)

		postTransform(outputFile)
	}

	abstract protected String getExtension()

	protected void preTransform(Transformer transformer, File sourceFile, File outputFile) {
	}

	protected void postTransform(File outputFile) {
		copyImages(project, "${project.buildDir}/reference/${xdir}/images")
		copyCss(project, xdir)
	}

	/**
	 * @param sourceDir directory of unfiltered sources
	 * @return directory of filtered sources
	 */
	private File filterDocbookSources(File sourceDir) {
		def workDir = new File("${project.buildDir}/reference-work")
		workDir.mkdirs()

		def expandables = project.reference.expandPlaceholders.split(',')
		logger.debug('Files that will have placeholders expanded:' + expandables)

		// copy everything but those requiring expansion
		project.copy {
			into(workDir)
			from(sourceDir) { exclude expandables }
		}

		// copy and expand ${...} variables along the way
		// e.g.: ${version} needs to be replaced in the header
		project.copy {
			into(workDir)
			from(sourceDir) { include expandables  }
			expand(version: "${project.version}")
		}

		// Copy and process any custom titlepages
		File titlePageSource = new File(sourceDir, "titlepage")
		if(titlePageSource.exists()) {
			def titlePageWorkDir = new File(new File(workDir, "xsl"), "titlepage")
			titlePageWorkDir.mkdirs()
			Transformer transformer = new TransformerFactoryImpl().newTransformer(
				new StreamSource(this.class.classLoader.getResourceAsStream("docbook/template/titlepage.xsl")))
			transformer.setParameter("ns", "http://www.w3.org/1999/xhtml")
			titlePageSource.eachFileMatch( ~/.*\.xml/, { f ->
				File output = new File(titlePageWorkDir, f.name.replace(".xml", ".xsl"))
				transformer.transform(new StreamSource(f), new StreamResult(output))
				// Ugly hack to work around Java XSLT bug
				output.setText(output.text.replaceFirst("xsl:stylesheet", "xsl:stylesheet xmlns:exsl=\"http://exslt.org/common\" "))
			})
		}
		return workDir
	}

	private void unpack() {
		def resourcesZipPath = 'META-INF/docbook-resources.zip'
		def resourcesZip = this.class.classLoader.getResource(resourcesZipPath)
		if (resourcesZip == null) {
			throw new GradleException("could not find ${resourcesZipPath} on the classpath")
		}
		// the file is a jar:file - write it to disk first
		def zipInputStream = resourcesZip.getContent()
		def zipFile = new File("${project.buildDir}/docbook-resources.zip")
		copyFile(zipInputStream, zipFile)
		project.copy {
			from project.zipTree(zipFile)
			into "${project.buildDir}/docbook-resources"
		}
	}

	static void copyFile(InputStream source, File destFile) {
		destFile.createNewFile()
		copy(source, new FileOutputStream(destFile), true)
	}

	static void copy(InputStream source, OutputStream destination, boolean closeDestination) {
		try {
			byte[] buffer = new byte[4096]
			int bytesRead
			while ((bytesRead = source.read(buffer)) > 0) {
				destination.write(buffer, 0, bytesRead)
			}
		} finally {
			if (source != null) {
				source.close()
			}
			if (destination != null && closeDestination) {
				destination.close()
			}
		}
	}


	// for some reason, statically typing the return value leads to the following
	// error when Gradle tries to subclass the task class at runtime:
	// java.lang.NoClassDefFoundError: org/apache/xml/resolver/CatalogManager
	private Object createCatalogManager() {
		CatalogManager manager = new CatalogManager()
		manager.setIgnoreMissingProperties(true)
		ClassLoader classLoader = this.getClass().getClassLoader()
		StringBuilder builder = new StringBuilder()
		String docbookCatalogName = "docbook/catalog.xml"
		URL docbookCatalog = classLoader.getResource(docbookCatalogName)

		if (docbookCatalog == null) {
			throw new IllegalStateException("Docbook catalog " + docbookCatalogName + " could not be found in " + classLoader)
		}

		builder.append(docbookCatalog.toExternalForm())

		Enumeration enumeration = classLoader.getResources("/catalog.xml")
		while (enumeration.hasMoreElements()) {
			builder.append('')
			URL resource = (URL) enumeration.nextElement()
			builder.append(resource.toExternalForm())
		}
		String catalogFiles = builder.toString()
		manager.setCatalogFiles(catalogFiles)
		return manager
	}

	protected void copyImages(def project, def dir) {
		// copy plugin provided resources first
		project.copy {
			into dir
			from "${project.buildDir}/docbook-resources/images"
		}

		// allow for project provided resources to override
		project.copy {
			into dir
			from "${sourceDir}/images"
		}
	}

	protected String copyCss(def project, def dir) {
		def targetPath = "${project.buildDir}/reference/${dir}/css"

		// copy plugin provided resources first
		project.copy {
			into targetPath
			from "${project.buildDir}/docbook-resources/css"
		}

		// allow for project provided resources to override
		project.copy {
			into targetPath
			from "${sourceDir}/css"
		}

		return targetPath
	}
}

class HtmlSingleDocbookReferenceTask extends AbstractDocbookReferenceTask {

	public HtmlSingleDocbookReferenceTask() {
		setDescription('Generates single-page HTML reference documentation.')
		stylesheet =  "html-singlepage.xsl"
		xdir = 'htmlsingle'
	}

	@Override
	protected String getExtension() {
		return 'html'
	}
}


class HtmlMultiDocbookReferenceTask extends AbstractDocbookReferenceTask {

	public HtmlMultiDocbookReferenceTask() {
		setDescription('Generates multi-page HTML reference documentation.')
		stylesheet = "html-multipage.xsl"
		xdir = 'html'
	}

	@Override
	protected String getExtension() {
		return 'html'
	}

	@Override
	protected void preTransform(Transformer transformer, File sourceFile, File outputFile) {
		String rootFilename = outputFile.getName()
		rootFilename = rootFilename.substring(0, rootFilename.lastIndexOf('.'))
		transformer.setParameter("root.filename", rootFilename)
		transformer.setParameter("base.dir", outputFile.getParent() + File.separator)
	}
}


class PdfDocbookReferenceTask extends AbstractDocbookReferenceTask {

	public PdfDocbookReferenceTask() {
		setDescription('Generates PDF reference documentation.')
		stylesheet = "pdf.xsl"
		xdir = 'pdf'
	}

	@Override
	protected String getExtension() {
		return 'fo'
	}

	/**
	 * <a href="https://xmlgraphics.apache.org/fop/0.95/embedding.html#render">From the FOP usage guide</a>
	 */
	@Override
	protected void postTransform(File foFile) {
		String imagesPath = "${project.buildDir}/reference/${xdir}/images"
		copyImages(project, imagesPath)

		FopFactory fopFactory = FopFactory.newInstance()
		if (project.reference.fopUserConfig != null) {
			fopFactory.setUserConfig(project.reference.fopUserConfig)
		}
		fopFactory.setBaseURL(project.file("${project.buildDir}/reference/pdf").toURI().toURL().toExternalForm())

		OutputStream out = null
		final File pdfFile = getPdfOutputFile(foFile)
		logger.debug("Transforming 'fo' file " + foFile + " to PDF: " + pdfFile)

		try {
			out = new BufferedOutputStream(new FileOutputStream(pdfFile))

			Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, out)

			TransformerFactory factory = TransformerFactory.newInstance()
			Transformer transformer = factory.newTransformer()

			Source src = new StreamSource(foFile)
			src.setSystemId(foFile.toURI().toURL().toExternalForm())

			Result res = new SAXResult(fop.getDefaultHandler())

			transformer.transform(src, res)

		} finally {
			if (out != null) {
				out.close()
			}
		}

		if (!project.reference.retainFo && !foFile.delete()) {
			logger.warn("Failed to delete 'fo' file " + foFile)
		}

		if (!project.delete(imagesPath)) {
			logger.warn("Failed to delete 'images' path " + imagesPath)
		}
	}

	private File getPdfOutputFile(File foFile) {
		return new File(foFile.parent, project.reference.pdfFilename)
	}

}


class EpubDocbookReferenceTask extends AbstractDocbookReferenceTask {

	public EpubDocbookReferenceTask() {
		setDescription('Generates EPUB reference documentation.')
		stylesheet = "epub.xsl"
		xdir = 'epub'
	}

	@Override
	protected String getExtension() {
		return 'epub'
	}

	@Override
	protected void preTransform(Transformer transformer, File sourceFile, File outputFile) {
		def workDir = new File("${project.buildDir}/reference-epub-work")
		workDir.mkdirs()
		File images = new File(workDir, "images")
		images.mkdirs()
		copyImages(project, images)
		String rootFilename = project.reference.epubFilename
		rootFilename = rootFilename.substring(0, rootFilename.lastIndexOf('.'))
		transformer.setParameter("root.filename", rootFilename)
		transformer.setParameter("base.dir", workDir.getPath() + File.separator)
		transformer.setParameter("epub.package.dir",  workDir.getPath() + File.separator)
		transformer.setParameter("epub.metainf.dir", File.separator + "META-INF" + File.separator)
		transformer.setParameter("chunk.base.dir", workDir.getPath() + File.separator)
		transformer.setParameter("epub.package.filename", "content.opf")
	}

	@Override
	protected void postTransform(File outputFile) {
		outputFile.delete()
		File workDir = new File("${project.buildDir}/reference-epub-work")
		InputStream container = getClass().getResourceAsStream("/epub/container.epub3.xml")
		copyFile(container, new File(workDir, "META-INF" + File.separator + "container.xml"))
		byte [] mimetypeData = "application/epub+zip".getBytes ("UTF-8")
		final File mimetype = new File(workDir, "mimetype")

		// Delete any existing file
		mimetype.delete ()

		ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(
			new File(outputFile.parent, project.reference.epubFilename)))

		// Write the mimetype entry first
		CRC32 crc = new CRC32 ()
		crc.update(mimetypeData)
		ZipEntry entry = new ZipEntry ("mimetype")
		entry.setMethod(ZipEntry.STORED)
		entry.setSize(mimetypeData.length )
		entry.setCrc(crc.getValue ())
		zip.putNextEntry(entry)
		zip.write(mimetypeData)
		zip.closeEntry()

		// Write the contents
		writeZip(zip, "", workDir)

		zip.close()
	}

	private void writeZip(ZipOutputStream zip, String prefix, File dir) {
		File[] files = dir.listFiles()
		for(File file in files) {
			if(file.isFile()) {
				FileInputStream inputStream = new FileInputStream(file)
				zip.putNextEntry(new ZipEntry(prefix + file.getName()))
				copy(inputStream, zip, false)
				zip.closeEntry()
			}
			if(file.isDirectory() && !file.getName().startsWith('.')) {
				zip.putNextEntry(new ZipEntry(prefix + file.getName() + "/"))
				zip.closeEntry()
				writeZip(zip, prefix + file.getName() + "/", file)
			}
		}
	}

}

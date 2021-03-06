import sbt._
import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities
import scala.xml.Text

object WebStartPlugin extends Plugin {
  //------------------------------------------------------------------------------
  //## configuration objects

  case class GenConf(
    dname: String,
    validity: Int)

  case class KeyConf(
    keyStore: File,
    storePass: String,
    alias: String,
    keyPass: String)

  case class JnlpConf(
    fileName: String,
    codeBase: String,
    title: String,
    vendor: String,
    description: String,
    iconName: Option[String],
    splashName: Option[String],
    offlineAllowed: Boolean,
    // NOTE if this is true, signing is mandatory
    allPermissions: Boolean,
    javaVersions: List[String],
    maxHeapSize: Int)

  case class AppletDescConf(
    name: String,
    width: Int,
    height: Int)

  //------------------------------------------------------------------------------
  //## exported

  val webstartKeygen = TaskKey[Unit]("webstart-keygen")
  val webstartBuild = TaskKey[File]("webstart")
  val webstartAssets = TaskKey[Seq[Asset]]("webstart-assets")
  val webstartSingleJarAssets = TaskKey[Seq[Asset]]("webstart-singlejar-assets")
  val webstartSingleJarBuild = TaskKey[File]("webstart-singlejar")
  val webstartOutputDirectory = SettingKey[File]("webstart-output-directory")
  val webstartResources = SettingKey[PathFinder]("webstart-resources")
  val webstartMainClass = SettingKey[String]("webstart-main-class")
  val webstartApplet = SettingKey[AppletDescConf]("webstart-applet")
  val webstartGenConf = SettingKey[GenConf]("webstart-gen-conf")
  val webstartKeyConf = SettingKey[KeyConf]("webstart-key-conf")
  val webstartJnlpConf = SettingKey[JnlpConf]("webstart-jnlp-conf")
  val webstartExtraFiles = TaskKey[Seq[File]]("webstart-extra-files")
  //overrides the jar assets normally used and uses a single jar, useful with tools such as proguard
  val webstartSingleJar = SettingKey[String]("webstart-single-jar-filename")

  // webstartJnlp		<<= (Keys.name) { it => it + ".jnlp" },
  lazy val allSettings = Seq(
    webstartKeygen <<= keygenTask,
    webstartBuild <<= buildTask(webstartAssets),
    webstartAssets <<= assetsTask,
    webstartSingleJarBuild <<= buildTask(webstartSingleJarAssets),
    webstartSingleJarAssets <<= assetsSingleJarTask,
    webstartOutputDirectory <<= (Keys.crossTarget) { _ / "webstart" },
    webstartResources <<= (Keys.sourceDirectory in Runtime) { _ / "webstart" },
    webstartMainClass := null,
    webstartApplet := null,
    webstartGenConf := null,
    webstartKeyConf := null,
    webstartJnlpConf := null,
    webstartSingleJar := null,
    webstartExtraFiles := Seq.empty
  )

  case class Asset(main: Boolean, fresh: Boolean, jar: File) {
    val name: String = jar.getName
  }

  //------------------------------------------------------------------------------
  //## tasks

  //  private def buildSingleJarTask: Initialize[Task[File]] = {
  //
  //    (Keys.streams, webstartSingleJarAssets, webstartMainClass, webstartKeyConf, webstartJnlpConf, webstartResources, webstartExtraFiles, webstartOutputDirectory, webstartSingleJarFile) map {
  //      (streams: TaskStreams, assets, mainClass: String, keyConf: KeyConf, jnlpConf: JnlpConf, webstartResources: PathFinder, extraFiles: Seq[File], outputDirectory: File, singleJarFile: File) =>
  //        {          
  //          require(mainClass != null, webstartMainClass.key.label + " must be set")
  //          require(jnlpConf != null, webstartJnlpConf.key.label + " must be set")
  //       
  //          build(streams, assets, mainClass, keyConf, jnlpConf, webstartResources, extraFiles, outputDirectory)
  //        }
  //    }
  //  }

  private def buildTask(webAssets: TaskKey[Seq[Asset]]): Initialize[Task[File]] = {

    (Keys.streams, webAssets, webstartMainClass, webstartApplet, webstartKeyConf, webstartJnlpConf, webstartResources, webstartExtraFiles, webstartOutputDirectory) map {
      (streams: TaskStreams, assets, mainClass: String, applet: AppletDescConf, keyConf: KeyConf, jnlpConf: JnlpConf, webstartResources: PathFinder, extraFiles: Seq[File], outputDirectory: File) =>
        {
          require(mainClass != null, webstartMainClass.key.label + " or must be defined");
          require(jnlpConf != null, webstartJnlpConf.key.label + " must be set")

          val freshAssets = assets filter { _.fresh }

          if (keyConf != null && freshAssets.nonEmpty) {
            streams.log info ("signing jars")
            freshAssets foreach { asset =>
              signAndVerify(keyConf, asset.jar, streams.log)
            }
          } else if (keyConf == null) {
            streams.log info ("missing KeyConf, leaving jar files unsigned")
          } else {
            streams.log info ("no fresh jars to sign")
          }

          // @see http://download.oracle.com/javase/tutorial/deployment/deploymentInDepth/jnlpFileSyntax.html
          streams.log info ("creating jnlp descriptor")
          val jnlpFile = outputDirectory / jnlpConf.fileName
          writeJnlp(jnlpConf, assets, mainClass, applet, jnlpFile)

          // TODO check
          // Keys.defaultExcludes
          streams.log info ("copying resources")
          val resourcesToCopy =
            for {
              dir <- webstartResources.get
              file <- dir.***.get
              target = Path.rebase(dir, outputDirectory)(file).get
            } yield (file, target)
          val resourcesCopied = IO copy resourcesToCopy

          streams.log info ("copying extra files")
          val extraCopied = IO copy (extraFiles map { it => (it, outputDirectory / it.getName) })

          streams.log info ("cleaning up")
          val allFiles = (outputDirectory * "*").get.toSet
          val assetJars = assets map { _.jar }
          val obsolete = allFiles -- assetJars -- resourcesCopied -- extraCopied - jnlpFile
          IO delete obsolete

          outputDirectory

        }
    }
  }

  private def signAndVerify(keyConf: KeyConf, jar: File, log: Logger) {
    // sigfile, storetype, provider, providerName
    val rc1 = Process("jarsigner", List(
      // "-verbose",
      "-keystore", keyConf.keyStore.getAbsolutePath,
      "-storepass", keyConf.storePass,
      "-keypass", keyConf.keyPass,
      // TODO makes the vm crash???
      // "-signedjar",	jar.getAbsolutePath,
      jar.getAbsolutePath,
      keyConf.alias
    )) ! log
    if (rc1 != 0) sys error ("sign failed: " + rc1)

    val rc2 = Process("jarsigner", List(
      "-verify",
      "-keystore", keyConf.keyStore.getAbsolutePath,
      "-storepass", keyConf.storePass,
      "-keypass", keyConf.keyPass,
      jar.getAbsolutePath
    )) ! log
    if (rc2 != 0) sys error ("verify failed: " + rc2)
  }

  //JaNeLa http://pscode.org/janela/
  //The Java Network Launch Analyzer (JaNeLA) is a tool designed to check
  //aspects of the JNLP file(s) and resources intended for the JWS based launch of a rich-client Java application.
  //JNLP file syntax: http://download.oracle.com/javase/tutorial/deployment/deploymentInDepth/jnlpFileSyntax.html
  private def writeJnlp(jnlpConf: JnlpConf, assets: Seq[Asset], mainClass: String, applet: AppletDescConf, targetFile: File) {
    implicit def optStrToOptText(opt: Option[String]) = opt map Text

    def applicationDesc = <application-desc main-class={ mainClass }/>
    def appletDesc = <applet-desc name={ applet.name } main-class={ mainClass } width={ applet.width.toString } height={ applet.height.toString }/>
    def appDesc = if (applet != null) appletDesc else applicationDesc;

    val xml =
      """<?xml version="1.0" encoding="utf-8"?>""" + "\n" +
        <jnlp spec="1.5+" codebase={ jnlpConf.codeBase } href={ jnlpConf.fileName }>
          <information>
            <title>{ jnlpConf.title }</title>
            <vendor>{ jnlpConf.vendor }</vendor>
            <description>{ jnlpConf.description }</description>
            { jnlpConf.iconName.toSeq map { it => <icon href={ it }/> } }
            { jnlpConf.splashName.toSeq map { it => <icon href={ it } kind="splash"/> } }
            { if (jnlpConf.offlineAllowed) Seq(<offline-allowed/>) else Seq.empty }
          </information>
          <security>
            { if (jnlpConf.allPermissions) Seq(<all-permissions/>) else Seq.empty }
          </security>
          <resources>
            { //see http://www.oracle.com/technetwork/java/javase/autodownload-140472.html
              //available versions of JRE: http://java.com/inc/dtoolkit.xml
            }
            { jnlpConf.javaVersions map { jv => <java version={ jv } initial-heap-size="128m" max-heap-size={ jnlpConf.maxHeapSize + "m" } href="http://java.sun.com/products/autodl/j2se"/> } }
            { assets map { it => <jar href={ it.name } main={ it.main.toString }/> } }
          </resources>
          { appDesc }
        </jnlp>
    IO write (targetFile, xml)
  }

  //------------------------------------------------------------------------------
  //## jar files

  def copiedJars(jarsToCopy: Seq[java.io.File], isMain: File => Boolean, outputDirectory: File) = {
    jarsToCopy map { source =>
      val main = isMain(source)
      val target = outputDirectory / source.getName
      val fresh = copyArchive(source, target)
      Asset(main, fresh, target)
    }
  }

  def logFreshAndUnchangedJars(assets: Seq[Asset], streams: TaskStreams) {
    val (freshAssets, unchangedAssets) = assets partition { _.fresh }
    streams.log info (freshAssets.size + " fresh jars, " + unchangedAssets.size + " unchanged jars")
  }

  private def assetsSingleJarTask: Initialize[Task[Seq[Asset]]] = {
    (Keys.crossTarget, webstartSingleJar, Keys.streams, Keys.products in Runtime, Keys.fullClasspath in Runtime, Keys.cacheDirectory, webstartOutputDirectory) map {
      (crossTarg: File, singleJarFileName: String, streams: TaskStreams, products, fullClasspath, cacheDirectory: File, outputDirectory: File) =>
        {
          require(singleJarFileName != null, webstartSingleJar.key.label + " must be set")

          streams.log info ("using single jar: " + singleJarFileName)

          val singleJarSeq = Seq(crossTarg / singleJarFileName)

          streams.log info ("copying single jar")

          val assets = copiedJars(singleJarSeq, _ => true, outputDirectory);

          logFreshAndUnchangedJars(assets, streams)

          assets

        }
    }
  }

  private def assetsTask: Initialize[Task[Seq[Asset]]] = {
    // BETTER use dependencyClasspath and products instead of fullClasspath?
    // BETTER use exportedProducts instead of products?
    (Keys.streams, Keys.products in Runtime, Keys.fullClasspath in Runtime, Keys.cacheDirectory, webstartOutputDirectory) map {
      (streams: TaskStreams, products, fullClasspath, cacheDirectory: File, outputDirectory: File) =>
        {
          // NOTE for directories, the package should be named after the artifact they come from
          val (archives, directories) = fullClasspath.files.distinct partition ClasspathUtilities.isArchive

          streams.log info ("creating directory jars")
          val directoryAssets = directories.zipWithIndex map {
            case (source, index) =>
              val main = products contains source
              val cache = cacheDirectory / webstartAssets.key.label / index.toString
              val target = outputDirectory / (index + ".jar")
              val fresh = jarDirectory(source, cache, target)
              Asset(main, fresh, target)
          }

          streams.log info ("copying library jars")
          val assets = copiedJars(archives, products contains _, outputDirectory) ++ directoryAssets

          logFreshAndUnchangedJars(assets, streams)

          assets
        }
    }
  }

  private def copyArchive(sourceFile: File, targetFile: File): Boolean = {
    val fresh = !targetFile.exists || sourceFile.lastModified > targetFile.lastModified
    if (fresh) {
      IO copyFile (sourceFile, targetFile)
    }
    fresh
  }

  private def jarDirectory(sourceDir: File, cacheDir: File, targetFile: File): Boolean = {
    import Predef.{ conforms => _, _ }
    import collection.JavaConversions._
    import Types.:+:

    import sbinary.{ DefaultProtocol, Format }
    import DefaultProtocol.{ FileFormat, immutableMapFormat, StringFormat, UnitFormat }
    import Cache.{ defaultEquiv, hConsCache, hNilCache, streamFormat, wrapIn }
    import Tracked.{ inputChanged, outputChanged }
    import FileInfo.exists
    import FilesInfo.lastModified

    implicit def stringMapEquiv: Equiv[Map[File, String]] = defaultEquiv

    val sources = (sourceDir ** -DirectoryFilter get) x (Path relativeTo sourceDir)

    def makeJar(sources: Seq[(File, String)], jar: File) {
      IO delete jar
      IO zip (sources, jar)
    }

    val cachedMakeJar = inputChanged(cacheDir / "inputs") { (inChanged, inputs: (Map[File, String] :+: FilesInfo[ModifiedFileInfo] :+: HNil)) =>
      val sources :+: _ :+: HNil = inputs
      outputChanged(cacheDir / "output") { (outChanged, jar: PlainFileInfo) =>
        val fresh = inChanged || outChanged
        if (fresh) {
          makeJar(sources.toSeq, jar.file)
        }
        fresh
      }
    }
    val sourcesMap = sources.toMap
    val inputs = sourcesMap :+: lastModified(sourcesMap.keySet.toSet) :+: HNil
    val fresh: Boolean = cachedMakeJar(inputs)(() => exists(targetFile))
    fresh
  }

  //------------------------------------------------------------------------------

  private def keygenTask: Initialize[Task[Unit]] = {
    (Keys.streams, webstartGenConf, webstartKeyConf) map {
      (streams: TaskStreams, genConf: GenConf, keyConf: KeyConf) =>
        {
          streams.log info ("creating webstart key in " + keyConf.keyStore)
          require(genConf != null, webstartGenConf.key.label + " must be set")
          require(keyConf != null, webstartKeyConf.key.label + " must be set")
          genkey(keyConf, genConf, streams.log)
        }
    }
  }

  private def genkey(keyConf: KeyConf, genConf: GenConf, log: Logger) {
    val rc = Process("keytool", List(
      "-genkey",
      "-dname", genConf.dname,
      "-validity", genConf.validity.toString,
      "-keystore", keyConf.keyStore.getAbsolutePath,
      "-storePass", keyConf.storePass,
      "-keypass", keyConf.keyPass,
      "-alias", keyConf.alias
    )) ! log
    if (rc != 0) sys error ("key gen failed: " + rc)
  }
}

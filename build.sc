import mill._, mill.scalalib._, mill.scalalib.publish._
import scalafmt._
import $ivy.`com.goyeau::mill-scalafix::0.3.1`, com.goyeau.mill.scalafix.ScalafixModule

object versions {
  val scala           = "2.13.10"
  val chisel3         = "3.6.0"
  val chisel3circt    = "0.8.0"
  val chiseltest      = "0.6.0"
  val scalatest       = "3.2.16"
  val organizeimports = "0.6.0"
  val semanticdb      = "4.5.13"
  val riscvassembler  = "1.8.0"
  val mainargs        = "0.5.0"
  val oslib           = "0.9.1"
}

trait BaseProject extends ScalaModule with PublishModule {
  def scalaVersion   = versions.scala
  def publishVersion = "1.0.0"
  def projectName    = "chiselv"
  def pomSettings = PomSettings(
    description    = "ChiselV is a RISC-V core written in Chisel",
    organization   = "com.carlosedp",
    url            = "https://github.com/carlosedp/chiselv",
    licenses       = Seq(License.MIT),
    versionControl = VersionControl.github("carlosedp", "chiselv"),
    developers = Seq(
      Developer("carlosedp", "Carlos Eduardo de Paula", "https://github.com/carlosedp"),
    ),
  )

  def repositoriesTask = T.task { // Add snapshot repositories in case needed
    super.repositoriesTask() ++ Seq("oss", "s01.oss")
      .map(r => s"https://$r.sonatype.org/content/repositories/snapshots")
      .map(coursier.maven.MavenRepository(_))
  }

  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"com.carlosedp::riscvassembler:${versions.riscvassembler}",
    ivy"com.lihaoyi::mainargs:${versions.mainargs}",
    ivy"com.lihaoyi::os-lib:${versions.oslib}",
  )
}

trait HasChisel3 extends ScalaModule {
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"edu.berkeley.cs::chisel3:${versions.chisel3}",
  )
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
    ivy"edu.berkeley.cs:::chisel3-plugin:${versions.chisel3}",
  )
  // TODO: Run in parallel
  object test extends Tests with TestModule.ScalaTest {
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.scalatest::scalatest:${versions.scalatest}",
      ivy"edu.berkeley.cs::chiseltest:${versions.chiseltest}",
    )
  }
}

trait CodeQuality extends ScalafixModule with ScalafmtModule {
  def scalafixIvyDeps = Agg(ivy"com.github.liancheng::organize-imports:${versions.organizeimports}")
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
    ivy"org.scalameta:::semanticdb-scalac:${versions.semanticdb}",
  )
}

trait ScalacOptions extends ScalaModule {
  override def scalacOptions = T {
    super.scalacOptions() ++ Seq(
      "-unchecked",
      "-deprecation",
      "-language:reflectiveCalls",
      "-feature",
      "-Xcheckinit",
      "-Xfatal-warnings",
      "-Ywarn-dead-code",
      "-Ywarn-unused",
      "-Ymacro-annotations",
    )
  }
}

object chiselv extends BaseProject with HasChisel3 with CodeQuality with ScalacOptions {
  def mainClass = Some("chiselv.Toplevel")
}
object chiselv_rvfi extends BaseProject with HasChisel3 with CodeQuality with ScalacOptions {
  def mainClass = Some("chiselv.RVFI")
  def sources   = T.sources(millSourcePath / os.up / "chiselv" / "src")
}

// Toplevel commands and aliases
def runTasks(
  t: Seq[String],
)(
  implicit ev: eval.Evaluator,
) = T.task {
  mill.main.MainModule.evaluateTasks(
    ev,
    t.flatMap(x => x +: Seq("+")).flatMap(x => x.split(" ")).dropRight(1),
    mill.define.SelectMode.Separated,
  )(identity)
}
def lint(
  implicit ev: eval.Evaluator,
) = T.command {
  runTasks(Seq("__.fix", "mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources"))
}

def deps(
  implicit ev: eval.Evaluator,
) = T.command {
  mill.scalalib.Dependency.showUpdates(ev)
}

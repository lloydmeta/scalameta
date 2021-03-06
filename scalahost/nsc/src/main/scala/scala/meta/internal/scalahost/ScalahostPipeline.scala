package scala.meta.internal
package scalahost

import java.io._
import java.net.URI
import scala.collection.mutable
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent
import scala.{meta => m}
import scala.meta.io._
import scala.meta.internal.semantic.DatabaseOps
import scala.meta.internal.semantic.{vfs => v}
import scala.meta.internal.semantic.{schema => s}

trait ScalahostPipeline extends DatabaseOps { self: ScalahostPlugin =>

  object ScalahostComponent extends PluginComponent {
    val global: ScalahostPipeline.this.global.type = ScalahostPipeline.this.global
    val runsAfter = List("typer")
    override val runsRightAfter = Some("typer")
    val phaseName = "scalameta"
    override val description = "compute the scala.meta semantic database"
    def newPhase(_prev: Phase) = new ScalahostPhase(_prev)

    class ScalahostPhase(prev: Phase) extends StdPhase(prev) {
      // NOTE: Here we encode assumptions that hold by design:
      //   * Output directory stores the semantic db generated by the previous compilation
      //     (that is necessary for incremental compilation support)
      //   * Working directory represents both previous and current compilation roots
      //     (that's the protocol that we assume that Scala build tools support)
      //   * All uris related to the previous semantic db are based on the file: protocol
      //     (that follows from the fact that the output directory is based on the file: protocol)
      val outputClasspath = Classpath(
        global.settings.outputDirs.getSingleOutput
          .map(_.file.getAbsolutePath)
          .getOrElse(global.settings.d.value))
      // NOTE: user.dir is not a great default for sourcepaths since it will include
      // irrelevant directories such as target. This parameter should ideall be passed by
      // the build integration as a compiler flag -Xplugin:scalahost:xxx.
      val assumedSourcepath = Sourcepath(sys.props("user.dir"))
      implicit class XtensionURI(uri: URI) { def toFile: File = new File(uri) }

      override def apply(unit: g.CompilationUnit): Unit = {
        val mminidb = m.Database(List(unit.source.toInput -> unit.toAttributes))
        mminidb.save(outputClasspath, assumedSourcepath)
      }

      override def run(): Unit = {
        val vdb = v.Database.load(outputClasspath)
        val orphanedVentries = vdb.entries.filter(ventry => {
          val scalaName = v.Paths.semanticdbToScala(ventry.fragment.name)
          assumedSourcepath.find(scalaName).isEmpty
        })
        orphanedVentries.map(ve => {
          def cleanupUpwards(file: File): Unit = {
            if (file.isFile) {
              file.delete()
            } else {
              if (file.getAbsolutePath == ve.base.toString) return
              if (file.listFiles.isEmpty) file.delete()
            }
            cleanupUpwards(file.getParentFile)
          }
          cleanupUpwards(ve.uri.toFile)
        })
        super.run()
      }
    }
  }
}

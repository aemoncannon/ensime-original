// Copyright: 2010 - 2017 https://github.com/ensime/ensime-server/graphs
// License: http://www.apache.org/licenses/LICENSE-2.0
package org.ensime.api

import java.io.File
import java.nio.file.{ Files, FileSystem, FileSystems, Path, Paths }
import java.net.{ URI, URL, URLDecoder }
import java.util.HashMap

import scala.annotation.StaticAnnotation

/**
 * Indicates that something will be removed.
 *
 * WORKAROUND https://issues.scala-lang.org/browse/SI-7934
 */
class deprecating(val detail: String = "") extends StaticAnnotation

sealed abstract class DeclaredAs(val symbol: scala.Symbol)

object DeclaredAs {
  case object Method extends DeclaredAs('method)
  case object Trait extends DeclaredAs('trait)
  case object Interface extends DeclaredAs('interface)
  case object Object extends DeclaredAs('object)
  case object Class extends DeclaredAs('class)
  case object Field extends DeclaredAs('field)
  case object Nil extends DeclaredAs('nil)

  def allDeclarations = Seq(Method, Trait, Interface, Object, Class, Field, Nil)
}

sealed trait FileEdit extends Ordered[FileEdit] {
  def file: File
  def text: String
  def from: Int
  def to: Int

  // Required as of Scala 2.11 for reasons unknown - the companion to Ordered
  // should already be in implicit scope
  import scala.math.Ordered.orderingToOrdered

  def compare(that: FileEdit): Int =
    (this.file, this.from, this.to, this.text).compare((that.file, that.from, that.to, that.text))
}

final case class TextEdit(file: File, from: Int, to: Int, text: String) extends FileEdit

// the next case classes have weird fields because we need the values in the protocol
final case class NewFile(file: File, from: Int, to: Int, text: String) extends FileEdit
object NewFile {
  def apply(file: File, text: String): NewFile = new NewFile(file, 0, text.length - 1, text)
}

final case class DeleteFile(file: File, from: Int, to: Int, text: String) extends FileEdit
object DeleteFile {
  def apply(file: File, text: String): DeleteFile = new DeleteFile(file, 0, text.length - 1, text)
}

sealed trait NoteSeverity
case object NoteError extends NoteSeverity
case object NoteWarn extends NoteSeverity
case object NoteInfo extends NoteSeverity
object NoteSeverity {
  def apply(severity: Int) = severity match {
    case 2 => NoteError
    case 1 => NoteWarn
    case 0 => NoteInfo
  }
}

sealed abstract class RefactorLocation(val symbol: Symbol)

object RefactorLocation {
  case object QualifiedName extends RefactorLocation('qualifiedName)
  case object File extends RefactorLocation('file)
  case object NewName extends RefactorLocation('newName)
  case object Name extends RefactorLocation('name)
  case object Start extends RefactorLocation('start)
  case object End extends RefactorLocation('end)
  case object MethodName extends RefactorLocation('methodName)
}

sealed abstract class RefactorType(val symbol: Symbol)

object RefactorType {
  case object Rename extends RefactorType('rename)
  case object ExtractMethod extends RefactorType('extractMethod)
  case object ExtractLocal extends RefactorType('extractLocal)
  case object InlineLocal extends RefactorType('inlineLocal)
  case object OrganizeImports extends RefactorType('organizeImports)
  case object AddImport extends RefactorType('addImport)
  case object ExpandMatchCases extends RefactorType('expandMatchCases)

  def allTypes = Seq(Rename, ExtractMethod, ExtractLocal, InlineLocal, OrganizeImports, AddImport, ExpandMatchCases)
}

/**
 * Represents a source file that has a physical location (either a
 * file or an archive entry) with (optional) up-to-date information in
 * another file, or as a String.
 *
 * Clients using a wire protocol should prefer `contentsIn` for
 * performance (string escaping), whereas in-process clients should
 * use the `contents` variant.
 *
 * If both contents and contentsIn are provided, contents is
 * preferred.
 *
 * Good clients provide the `id` field so the server doesn't have to
 * work it out all the time.
 */
final case class SourceFileInfo(
    file: EnsimeFile,
    contents: Option[String] = None,
    contentsIn: Option[File] = None,
    id: Option[EnsimeProjectId] = None
) {
  // keep the log file sane for unsaved files
  override def toString = s"SourceFileInfo($file,${contents.map(_ => "...")},$contentsIn)"
}

final case class OffsetRange(from: Int, to: Int)

@deprecating("move all non-model code out of the api")
object OffsetRange extends ((Int, Int) => OffsetRange) {
  def apply(fromTo: Int): OffsetRange = new OffsetRange(fromTo, fromTo)
}

// it would be good to expand this hierarchy and include information
// such as files/dirs, existance, content hints
// (java/scala/class/resource) in the type, validated at construction
// (and can be revalidated at any time)
sealed trait EnsimeFile {
  def path: Path
  def uriString: String = path.toUri.toASCIIString
  // PathMatcher is too complex, use http://stackoverflow.com/questions/20531247\
  def isScala: Boolean = {
    val str = getPathStr
    str.toLowerCase.endsWith(".scala")
  }

  def isJava: Boolean = {
    val str = getPathStr
    str.toLowerCase.endsWith(".java")
  }

  private def getPathStr = this match {
    case RawFile(path) => path.toString
    case ArchiveFile(_, entry) => entry
  }

  def isJar: Boolean = path.toString.toLowerCase.endsWith(".jar")
  def isClass: Boolean = path.toString.toLowerCase.endsWith(".class")
  def pathWithinArchive: Option[String] = {
    if (uriString.startsWith("jar") || uriString.startsWith("zip"))
      Some(path.toString)
    else
      None
  }

  def exists: Boolean = this match {
    case RawFile(path) => Files.exists(path)
    case ArchiveFile(path, entry) => Files.exists(path) && withEntry(p => Files.exists(p), entry)
  }

  private def fileSystem(): FileSystem = FileSystems.newFileSystem(
    URI.create(s"jar:${path.toUri}"),
    new HashMap[String, String]
  )

  private def withFs[T](action: FileSystem => T): T = {
    val fs = fileSystem()
    try action(fs)
    finally fs.close()
  }

  private def withEntry[T](action: Path => T, entry: String): T = withFs { fs =>
    action(fs.getPath(entry))
  }
}

object EnsimeFile {
  private val ArchiveRegex = "(?:(?:jar:)?file:)?([^!]++)!(.++)".r
  private val FileRegex = "(?:(?:jar:)?file:)?(.++)".r

  def apply(name: String): EnsimeFile = name match {
    case ArchiveRegex(file, entry) => ArchiveFile(Paths.get(cleanBadWindows(file)), entry)
    case FileRegex(file) => RawFile(Paths.get(cleanBadWindows(file)))
  }

  // URIs on Windows can look like /C:/path/to/file, which are malformed
  private val BadWindowsRegex = "/+([^:]+:[^:]+)".r
  private def cleanBadWindows(file: String): String = file match {
    case BadWindowsRegex(clean) => clean
    case other => other
  }

  //TODO: Used anywhere?
  def apply(url: URL): EnsimeFile = EnsimeFile(URLDecoder.decode(url.toExternalForm(), "UTF-8"))

  def apply(file: File): EnsimeFile = RawFile(file.toPath)
}

final case class RawFile(path: Path) extends EnsimeFile
/**
 * @param path the container of entry (in nio terms, the FileSystem)
 * @param entry is relative to the container (this needs to be loaded by a FileSystem to be usable)
 */
final case class ArchiveFile(path: Path, entry: String) extends EnsimeFile

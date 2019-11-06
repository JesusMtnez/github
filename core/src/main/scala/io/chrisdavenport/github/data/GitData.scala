package io.chrisdavenport.github.data

import cats.implicits._
import org.http4s._
import org.http4s.circe._
import io.circe._
import io.circe.syntax._

object GitData {
  sealed trait Encoding extends Product with Serializable
  object Encoding {
    case object Base64 extends Encoding
    case object Utf8 extends Encoding

    implicit val encoder = new Encoder[Encoding]{
      def apply(a: Encoding): Json = a match {
        case Base64 => "base64".asJson
        case Utf8 => "utf-8".asJson
      }
    }
  }

  final case class Blob(
    content: String, // Base64 encoded, Caution Supports up to 100megabytes in size
    uri: Uri,
    sha: String,
    size: Int
  )
  object Blob {
    implicit val decoder = new Decoder[Blob]{
      def apply(c: HCursor): Decoder.Result[Blob] = 
        (
          c.downField("content").as[String],
          c.downField("url").as[Uri],
          c.downField("sha").as[String],
          c.downField("size").as[Int]
        ).mapN(Blob.apply)
    }
  }

  final case class CreateBlob(
    content: String,
    encoding: Encoding
  )

  object CreateBlob {
    implicit val encoder = new Encoder[CreateBlob]{
      def apply(a: CreateBlob): Json = Json.obj(
        "content" -> a.content.asJson,
        "encoding" -> a.encoding.asJson
      )
    }
  }

  final case class NewBlob(
    uri: Uri,
    sha: String
  )
  object NewBlob {
    implicit val decoder = new Decoder[NewBlob]{
      def apply(c: HCursor): Decoder.Result[NewBlob] = 
        (
          c.downField("url").as[Uri],
          c.downField("sha").as[String]
        ).mapN(NewBlob.apply)
    }
  }


  sealed trait GitTreeType extends Product with Serializable
  object GitTreeType {
    case object Blob extends GitTreeType
    case object Commit extends GitTreeType
    case object Tree extends GitTreeType

    implicit val decoder = new Decoder[GitTreeType]{
      def apply(c: HCursor): Decoder.Result[GitTreeType] =
        c.as[String].flatMap{
          case "blob" => Right(Blob)
          case "commit" => Right(Commit)
          case "tree" => Right(Tree)
          case other => DecodingFailure(s"GitTreeType got: $other", c.history).asLeft
        }
    }

    implicit val encoder = new Encoder[GitTreeType]{
      def apply(a: GitTreeType): Json = a match {
        case Blob => Json.fromString("blob")
        case Commit => Json.fromString("commit")
        case Tree => Json.fromString("tree")
      }
    }

  }

  sealed trait GitMode extends Product with Serializable
  object GitMode {
    case object Executable extends GitMode
    case object File extends GitMode    
    case object Subdirectory extends GitMode
    case object Submodule extends GitMode
    case object Symlink extends GitMode

    implicit val decoder = new Decoder[GitMode]{
      def apply(c: HCursor): Decoder.Result[GitMode] = c.as[String].flatMap{
        case "100755" => Right(Executable)
        case "100644" => Right(File)
        case "040000" => Right(Subdirectory)
        case "160000" => Right(Submodule)
        case "120000" => Right(Symlink)
        case other =>  DecodingFailure(s"GitMode got: $other", c.history).asLeft
      }
    }

    implicit val encoder = new Encoder[GitMode]{
      def apply(a: GitMode): Json = a match {
        case Executable => Json.fromString("100755")
        case File => Json.fromString("100644")
        case Subdirectory => Json.fromString("040000")
        case Submodule => Json.fromString("160000")
        case Symlink => Json.fromString("120000")
      }
    }

  }

  final case class GitTree(
    path: String,
    sha: String,
    `type`: GitTreeType,
    mode: GitMode,
    uri: Option[Uri],
    size: Option[Int],
  )
  object GitTree {
    implicit val decoder = new Decoder[GitTree]{
      def apply(c: HCursor): Decoder.Result[GitTree] = 
        (
          c.downField("path").as[String],
          c.downField("sha").as[String],
          c.downField("type").as[GitTreeType],
          c.downField("mode").as[GitMode],
          c.downField("url").as[Option[Uri]],
          c.downField("size").as[Option[Int]]
        ).mapN(GitTree.apply)
    }
  }

  final case class Tree(
    sha: String,
    uri: Uri,
    gitTrees: List[GitTree],
    truncated: Option[Boolean]
  )
  object Tree {
    implicit val decoder = new Decoder[Tree]{
      def apply(c: HCursor): Decoder.Result[Tree] = 
        (
          c.downField("sha").as[String],
          c.downField("url").as[Uri],
          c.downField("tree").as[List[GitTree]],
          c.downField("truncated").as[Option[Boolean]]
        ).mapN(Tree.apply)
    }
  }



  sealed trait CreateGitTree extends Product with Serializable
  object CreateGitTree {

    def fromGitTree(g: GitTree): CreateGitTree = 
      CreateGitTreeSha(
        g.path,
        g.sha.some,
        g.`type`,
        g.mode
      )

    final case class CreateGitTreeSha(
      path: String,
      sha: Option[String],
      `type`: GitTreeType,
      mode: GitMode
    ) extends CreateGitTree
    
    final case class CreateGitTreeBlob(
      path: String,
      content: String,
      mode: Either[GitMode.Executable.type, GitMode.File.type],
    ) extends CreateGitTree

    implicit val encoder = new Encoder[CreateGitTree]{
      def apply(a: CreateGitTree): Json = a match {
        case CreateGitTreeSha(path, sha, typ, mode) => 
          Json.obj(
            "path" -> path.asJson,
            "sha" -> sha.asJson,
            "type" -> typ.asJson,
            "mode" -> mode.asJson
          )
        case CreateGitTreeBlob(path, content, mode) => 
          Json.obj(
            "path" -> path.asJson,
            "type" -> "blob".asJson,
            "mode" -> mode.merge.asJson,
            "content" -> content.asJson
          )
        
      }
    }
  }

  /**
   * The tree creation API accepts nested entries. 
   * If you specify both a tree and a nested path modifying that tree,
   * this endpoint will overwrite the contents of the tree with the new path contents,
   * and create a new tree structure.
   * 
   * If you use this endpoint to add, delete, or modify the file contents in a tree,
   *  you will need to commit the tree and then update a branch to point to the commit.
   *  For more information see "Create a commit" and "Update a reference."
   * 
   * POST /repos/:owner/:repo/git/trees
   * 
   * @param tree Objects specifying the tree structure
   * @param baseTreeSha The SHA1 of the tree you want to update with new data. 
   *   If you don't set this, the commit will be created on top of everything;
   *   however, it will only contain your change, the rest of your files will show up as deleted.
   **/
  final case class CreateTree(
    tree: List[CreateGitTree],
    baseTreeSha: Option[String],
  )
  object CreateTree {
    implicit val encoder = new Encoder[CreateTree]{
      def apply(a: CreateTree): Json = 
        Json.obj(
          "tree" -> a.tree.asJson,
          "base_tree" -> a.baseTreeSha.asJson
        )
    }
  }





}
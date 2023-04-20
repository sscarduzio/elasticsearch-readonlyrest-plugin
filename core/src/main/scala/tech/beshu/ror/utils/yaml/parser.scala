/*
 * Copyright https://github.com/circe/circe-yaml
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tech.beshu.ror.utils.yaml

import java.io.{Reader, StringReader}
import cats.syntax.either._
import io.circe._
import tech.beshu.ror.org.yaml.snakeyaml.{LoaderOptions, Yaml}
import tech.beshu.ror.org.yaml.snakeyaml.constructor.SafeConstructor
import tech.beshu.ror.org.yaml.snakeyaml.nodes._

import scala.collection.JavaConverters._

object parser {
  /**
    * Parse YAML from the given [[Reader]], returning either [[ParsingFailure]] or [[Json]]
    *
    * @param yaml
    * @return
    */
  def parse(yaml: Reader): Either[ParsingFailure, Json] = for {
    parsed <- parseSingle(yaml)
    json <- yamlToJson(parsed)
  } yield json

  def parse(yaml: String): Either[ParsingFailure, Json] = parse(new StringReader(yaml))

  def parseDocuments(yaml: Reader): LazyList[Either[ParsingFailure, Json]] = parseStream(yaml).map(yamlToJson)

  def parseDocuments(yaml: String): LazyList[Either[ParsingFailure, Json]] = parseDocuments(new StringReader(yaml))

  private[this] def parseSingle(reader: Reader) = Either.catchNonFatal(
    new Yaml(new SafeConstructor(new LoaderOptions()))
      .compose(reader)).leftMap(err => ParsingFailure(err.getMessage, err)
  )

  private[this] def parseStream(reader: Reader) =
    new Yaml(new SafeConstructor(new LoaderOptions())).composeAll(reader).asScala.to(LazyList)

  private[this] object CustomTag {
    def unapply(tag: Tag): Option[String] = if (!tag.startsWith(Tag.PREFIX))
      Some(tag.getValue)
    else
      None
  }

  private[this] class FlatteningConstructor extends SafeConstructor(new LoaderOptions()) {
    def flatten(node: MappingNode): MappingNode = {
      flattenMapping(node)
      node
    }

    def construct(node: ScalarNode): Object = {
      getConstructor(node).construct(node)
    }
  }

  private[this] def yamlToJson(node: Node): Either[ParsingFailure, Json] = {
    // Isn't thread-safe internally, may hence not be shared
    val flattener: FlatteningConstructor = new FlatteningConstructor

    def convertScalarNode(node: ScalarNode) = Either.catchNonFatal(node.getTag match {
      case Tag.INT | Tag.FLOAT => JsonNumber.fromString(node.getValue).map(Json.fromJsonNumber).getOrElse {
        throw new NumberFormatException(s"Invalid numeric string ${node.getValue}")
      }
      case Tag.BOOL => Json.fromBoolean(flattener.construct(node) match {
        case b: java.lang.Boolean => b
        case _ => throw new IllegalArgumentException(s"Invalid boolean string ${node.getValue}")
      })
      case Tag.NULL => Json.Null
      case CustomTag(other) =>
        Json.fromJsonObject(JsonObject.singleton(other.stripPrefix("!"), Json.fromString(node.getValue)))
      case _ => Json.fromString(node.getValue)
    }).leftMap {
      err =>
        ParsingFailure(err.getMessage, err)
    }

    if (node == null) {
      Right(Json.False)
    } else {
      node match {
        case mapping: MappingNode =>
          val duplicatedKeyOrEmptyJson = mapping.getValue.asScala
            .foldLeft(Set[String]().asRight[ParsingFailure])(findDuplicatedKey)
            .map(_ => JsonObject.empty)
          flattener.flatten(mapping).getValue.asScala
            .foldLeft(duplicatedKeyOrEmptyJson) {
              (objEither, tup) =>
                for {
                  obj <- objEither
                  key <- convertKeyNode(tup.getKeyNode)
                  value <- yamlToJson(tup.getValueNode)
                } yield obj.add(key, value)
            }.map(Json.fromJsonObject)
        case sequence: SequenceNode =>
          sequence.getValue.asScala.foldLeft(Either.right[ParsingFailure, List[Json]](List.empty[Json])) {
            (arrEither, node) =>
              for {
                arr <- arrEither
                value <- yamlToJson(node)
              } yield value :: arr
          }.map(arr => Json.fromValues(arr.reverse))
        case scalar: ScalarNode => convertScalarNode(scalar)
      }
    }
  }

  private def findDuplicatedKey(acc: Either[ParsingFailure, Set[String]],
                                tuple: NodeTuple): Either[ParsingFailure, Set[String]] = {
    acc.flatMap { keys =>
      convertKeyNode(tuple.getKeyNode)
        .flatMap { key =>
          if (keys.contains(key)) {
            ParsingFailure(s"Duplicated key: '$key'", DuplicatedKeyException(key)).asLeft
          } else {
            (keys + key).asRight
          }
        }
    }
  }

  private def convertKeyNode(node: Node): Either[ParsingFailure, String] = node match {
    case scalar: ScalarNode => Right(scalar.getValue)
    case _ =>
      val message = "Only string keys can be represented in JSON"
      Left(ParsingFailure(message, YamlParserException(message)))
  }

  final case class YamlParserException(message: String) extends Exception
  final case class DuplicatedKeyException(key: String) extends Exception
}

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
import tech.beshu.ror.org.yaml.snakeyaml.Yaml
import tech.beshu.ror.org.yaml.snakeyaml.constructor.SafeConstructor
import tech.beshu.ror.org.yaml.snakeyaml.nodes._

import scala.collection.JavaConverters._

object parser {
  /**
    * Parse YAML from the given [[Reader]], returning either [[ParsingFailure]] or [[Json]]
    * @param yaml
    * @return
    */
  def parse(yaml: Reader): Either[ParsingFailure, Json] = for {
    parsed <- parseSingle(yaml)
    json <- yamlToJson(parsed)
  } yield json

  def parse(yaml: String): Either[ParsingFailure, Json] = parse(new StringReader(yaml))

  def parseDocuments(yaml: Reader): Stream[Either[ParsingFailure, Json]] = parseStream(yaml).map(yamlToJson)

  def parseDocuments(yaml: String): Stream[Either[ParsingFailure, Json]] = parseDocuments(new StringReader(yaml))

  private[this] def parseSingle(reader: Reader) =
    Either.catchNonFatal(new Yaml().compose(reader)).leftMap(err => ParsingFailure(err.getMessage, err))

  private[this] def parseStream(reader: Reader) =
    new Yaml().composeAll(reader).asScala.toStream

  private[this] object CustomTag {
    def unapply(tag: Tag): Option[String] = if (!tag.startsWith(Tag.PREFIX))
      Some(tag.getValue)
    else
      None
  }

  private[this] class FlatteningConstructor extends SafeConstructor {
    def flatten(node: MappingNode): MappingNode = {
      flattenMapping(node)
      node
    }

    def construct(node: ScalarNode): Object = {
      getConstructor(node).construct(node)
    }
  }

  private[this] val flattener: FlatteningConstructor = new FlatteningConstructor

  private[this] def yamlToJson(node: Node): Either[ParsingFailure, Json] = {

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

    def convertKeyNode(node: Node) = node match {
      case scalar: ScalarNode => Right(scalar.getValue)
      case _ =>
        val message = "Only string keys can be represented in JSON"
        Left(ParsingFailure(message, YamlParserException(message)))
    }

    def checkDuplicates(jsonObject: JsonObject, key: String): Either[ParsingFailure, String] = {
      Either.cond(
        test = !jsonObject.contains(key),
        right = key,
        left = {
          val message = s"Duplicated key: '$key'"
          ParsingFailure(message, YamlParserException(message))
        })
    }

    if (node == null) {
      Right(Json.False)
    } else {
      node match {
        case mapping: MappingNode =>
          flattener.flatten(mapping).getValue.asScala.foldLeft(
            Either.right[ParsingFailure, JsonObject](JsonObject.empty)
          ) {
            (objEither, tup) =>
              for {
                obj <- objEither
                key <- convertKeyNode(tup.getKeyNode)
                value <- yamlToJson(tup.getValueNode)
                uniqueKey <- checkDuplicates(obj, key)
              } yield obj.add(uniqueKey, value)
          }.map(Json.fromJsonObject)
        case sequence: SequenceNode =>
          sequence.getValue.asScala.foldLeft(Either.right[ParsingFailure, List[Json]](List.empty[Json])) {
            (arrEither, node) => for {
              arr <- arrEither
              value <- yamlToJson(node)
            } yield value :: arr
          }.map(arr => Json.fromValues(arr.reverse))
        case scalar: ScalarNode => convertScalarNode(scalar)
      }
    }
  }

  final case class YamlParserException(message: String) extends Exception
}

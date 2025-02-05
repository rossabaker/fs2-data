/*
 * Copyright 2019 Lucas Satabin
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
package fs2
package data
package xml

sealed trait XmlEvent

object XmlEvent {

  sealed trait XmlTexty extends XmlEvent {
    def render: String
  }

  case object StartDocument extends XmlEvent

  case class XmlDecl(version: String, encoding: Option[String], standalone: Option[Boolean]) extends XmlEvent

  case class StartTag(name: QName, attributes: List[Attr], isEmpty: Boolean) extends XmlEvent

  case class XmlCharRef(value: Int) extends XmlTexty {
    def render = f"&#x$value%04x;"
  }

  case class XmlEntityRef(name: String) extends XmlTexty {
    def render = s"&$name;"
  }

  case class XmlString(s: String, isCDATA: Boolean) extends XmlTexty {
    def render = s
  }

  case class XmlPI(target: String, content: String) extends XmlEvent

  case class XmlDoctype(name: String, docname: String, systemid: Option[String]) extends XmlEvent

  case class EndTag(name: QName) extends XmlEvent

  case object EndDocument extends XmlEvent

  case class Comment(comment: String) extends XmlEvent

}

/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.gradle.utils;

import org.gradle.api.GradleException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Reads the small XML documents a Maven repository serves: POMs and artifact metadata. */
final class XmlDocuments {

  private XmlDocuments() {}

  /**
   * The root element of {@code xml}. {@code source} names what was parsed in the failure message. Parsing is
   * namespace-unaware, so elements are addressed by their plain name, and DOCTYPE declarations are rejected so
   * that a document fetched over the network cannot pull in external entities.
   */
  static Element rootOf(String xml, String source) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setNamespaceAware(false);
      return factory
          .newDocumentBuilder()
          .parse(new InputSource(new StringReader(xml)))
          .getDocumentElement();
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new GradleException("Cannot parse " + source + ": " + e.getMessage(), e);
    }
  }

  static List<Element> childrenNamed(Element parent, String name) {
    List<Element> elements = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
        elements.add((Element) child);
      }
    }
    return elements;
  }

  static Optional<Element> childNamed(Element parent, String name) {
    return childrenNamed(parent, name).stream().findFirst();
  }

  static Optional<String> childText(Element parent, String name) {
    return childNamed(parent, name).map(XmlDocuments::textOf);
  }

  static String requiredChildText(Element parent, String name) {
    return childText(parent, name)
        .orElseThrow(
            () -> new GradleException("<" + parent.getNodeName() + "> without a <" + name + ">"));
  }

  static String textOf(Element element) {
    return element.getTextContent().trim();
  }
}

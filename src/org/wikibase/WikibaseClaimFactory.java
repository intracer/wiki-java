/**
*  This program is free software; you can redistribute it and/or
*  modify it under the terms of the GNU General Public License
*  as published by the Free Software Foundation; either version 3
*  of the License, or (at your option) any later version. Additionally
*  this file is subject to the "Classpath" exception.
*
*  This program is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with this program; if not, write to the Free Software Foundation,
*  Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
*/
package org.wikibase;

import java.math.BigInteger;
import java.net.URL;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.DatatypeConverter;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.wikibase.data.Claim;
import org.wikibase.data.CommonsMedia;
import org.wikibase.data.Entity;
import org.wikibase.data.GlobeCoordinate;
import org.wikibase.data.Item;
import org.wikibase.data.LanguageString;
import org.wikibase.data.Property;
import org.wikibase.data.Quantity;
import org.wikibase.data.Rank;
import org.wikibase.data.Snak;
import org.wikibase.data.StringData;
import org.wikibase.data.Time;
import org.wikibase.data.URLData;

public class WikibaseClaimFactory {
    public static Claim fromNode(Node node) throws WikibaseException {
        if (!"claim".equalsIgnoreCase(node.getNodeName())) {
            return null;
        }

        Claim claim = new Claim();
        NamedNodeMap claimAttributes = node.getAttributes();
        claim.setId(claimAttributes.getNamedItem("id").getNodeValue());
        claim.setRank(Rank.fromString(claimAttributes.getNamedItem("rank").getNodeValue()));
        claim.setType(claimAttributes.getNamedItem("type").getNodeValue());

        Node claimChild = node.getFirstChild();
        while (null != claimChild) {
            if ("mainsnak".equalsIgnoreCase(claimChild.getNodeName())) {
                Property prop = WikibasePropertyFactory
                    .getWikibaseProperty(claimChild.getAttributes().getNamedItem("property").getNodeValue());
                Snak mainsnak = parseSnakFromNode(claimChild);
                claim.setMainsnak(mainsnak);
                claim.setProperty(prop);
            } else if ("references".equalsIgnoreCase(claimChild.getNodeName())) {
                Node crtReference = claimChild.getFirstChild();
                while (null != crtReference) {
                    Set<Snak> reference = new HashSet<Snak>();
                    if ("reference".equals(crtReference.getNodeName())) {
                        Node snaks = crtReference.getFirstChild();
                        if ("snaks".equalsIgnoreCase(snaks.getNodeName())) {
                            Node crtProperty = snaks.getFirstChild();
                            while (null != crtProperty) {
                                if ("property".equalsIgnoreCase(crtProperty.getNodeName())) {
                                    Node crtSnakNode = crtProperty.getFirstChild();
                                    while (null != crtSnakNode) {
                                        Snak snak = parseSnakFromNode(crtSnakNode);
                                        if (null != snak) {
                                            reference.add(snak);
                                        }
                                        crtSnakNode = crtSnakNode.getNextSibling();
                                    }
                                }
                                crtProperty = crtProperty.getNextSibling();
                            }
                        }
                    }
                    claim.addReference(reference);
                    crtReference = crtReference.getNextSibling();
                }
            } else if ("qualifiers".equalsIgnoreCase(claimChild.getNodeName())) {
                Node crtProperty = claimChild.getFirstChild();
                if ("property".equals(crtProperty.getNodeName())) {
                    while (null != crtProperty) {
                        if (!"property".equalsIgnoreCase(crtProperty.getNodeName())) {
                            crtProperty = crtProperty.getNextSibling();
                            continue;
                        }
                        Node crtQualifier = crtProperty.getFirstChild();

                        String propCode = crtProperty.getAttributes().getNamedItem("id").getNodeValue();
                        Property prop = WikibasePropertyFactory.getWikibaseProperty(propCode);
                        if ("qualifiers".equalsIgnoreCase(crtQualifier.getNodeName())) {
                            Snak dataSnak = parseSnakFromNode(crtQualifier);
                            if (null != dataSnak) {
                                claim.addQualifier(prop, dataSnak.getData());
                            }
                        }
                        crtProperty = crtProperty.getNextSibling();
                    }
                }
            }

            claimChild = claimChild.getNextSibling();
        }

        return claim;
    }

    private static Snak parseSnakFromNode(Node snakNode) throws WikibaseException {
        String datatype = snakNode.getAttributes().getNamedItem("datatype").getNodeValue();
        Property prop = new Property(snakNode.getAttributes().getNamedItem("property").getNodeValue());
        Snak snak = new Snak();
        snak.setProperty(prop);
        snak.setDatatype(datatype);
        if ("wikibase-item".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            while (null != datavalueNode) {
                if ("datavalue".equalsIgnoreCase(datavalueNode.getNodeName())) {
                    if ("wikibase-entityid"
                        .equalsIgnoreCase(datavalueNode.getAttributes().getNamedItem("type").getNodeValue())) {
                        Node valueNode = datavalueNode.getFirstChild();
                        while (null != valueNode) {
                            if ("value".equalsIgnoreCase(valueNode.getNodeName())) {
                                if ("item".equalsIgnoreCase(
                                    valueNode.getAttributes().getNamedItem("entity-type").getNodeValue())) {
                                    String itemId = valueNode.getAttributes().getNamedItem("numeric-id").getNodeValue();
                                    Entity ent = new Entity(itemId);
                                    snak.setData(new Item(ent));
                                }
                            }
                            valueNode = valueNode.getNextSibling();
                        }
                    }
                }
                datavalueNode = datavalueNode.getNextSibling();
            }

        } else if ("commonsMedia".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            while (null != datavalueNode) {
                if ("datavalue".equalsIgnoreCase(datavalueNode.getNodeName())) {
                    snak.setData(new CommonsMedia(datavalueNode.getAttributes().getNamedItem("value").getNodeValue()));
                }
                datavalueNode = datavalueNode.getNextSibling();
            }
        } else if ("string".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            while (null != datavalueNode) {
                if ("datavalue".equalsIgnoreCase(datavalueNode.getNodeName())) {
                    snak.setData(new StringData(datavalueNode.getAttributes().getNamedItem("value").getNodeValue()));
                }
                datavalueNode = datavalueNode.getNextSibling();
            }
        } else if ("url".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            while (null != datavalueNode) {
                if ("datavalue".equalsIgnoreCase(datavalueNode.getNodeName())) {
                    try {
                        String urlValue = datavalueNode.getAttributes().getNamedItem("value").getNodeValue();
                        snak.setData(new URLData(new URL(urlValue)));
                    } catch (Exception e) {
                        throw new WikibaseException(e);
                    }
                }
                datavalueNode = datavalueNode.getNextSibling();
            }
        } else if ("monolingualtext".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            while (null != datavalueNode) {
                if ("datavalue".equalsIgnoreCase(datavalueNode.getNodeName())) {
                    Node valueNode = datavalueNode.getFirstChild();
                    while (null != valueNode) {
                        if ("value".equalsIgnoreCase(valueNode.getNodeName())) {
                            try {
                                String language = valueNode.getAttributes().getNamedItem("language").getNodeValue();
                                String text = valueNode.getAttributes().getNamedItem("text").getNodeValue();
                                snak.setData(new LanguageString(language, text));
                            } catch (Exception e) {
                                throw new WikibaseException(e);
                            }
                        }
                        valueNode = valueNode.getNextSibling();
                    }
                }
                datavalueNode = datavalueNode.getNextSibling();
            }
        } else if ("time".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            while (null != datavalueNode) {
                if ("datavalue".equalsIgnoreCase(datavalueNode.getNodeName())) {
                    if ("time".equalsIgnoreCase(datavalueNode.getAttributes().getNamedItem("type").getNodeValue())) {
                        Node valueNode = datavalueNode.getFirstChild();
                        while (null != valueNode) {
                            if ("value".equalsIgnoreCase(valueNode.getNodeName())) {
                                String iso8601time = valueNode.getAttributes().getNamedItem("time").getNodeValue();
                                Time time = new Time();
                                time.setPrecision(
                                    Integer.parseInt(valueNode.getAttributes().getNamedItem("precision").getNodeValue()));
                                if (time.getPrecision() < 10) {
                                    Pattern yearExtractor = Pattern.compile("(\\+|\\-)?(\\d+).*");
                                    Matcher yearMatcher = yearExtractor.matcher(iso8601time);
                                    if (yearMatcher.matches()) {
                                        String yearStr = yearMatcher.group(2);
                                        String yearSignum = yearMatcher.group(1);
                                        BigInteger year = new BigInteger(yearSignum + yearStr);
                                        time.setYear(year.longValue());
                                    }
                                } else if (time.getPrecision() == 10) {
                                    Pattern yearMonthExtractor = Pattern.compile("(\\+|\\-)?(\\d+)\\-(\\d+).*");
                                    Matcher yearMonthMatcher = yearMonthExtractor.matcher(iso8601time);
                                    if (yearMonthMatcher.matches()) {
                                        String yearStr = yearMonthMatcher.group(2);
                                        String yearSignum = yearMonthMatcher.group(1);
                                        String monthStr = yearMonthMatcher.group(3);
                                        Calendar cal = GregorianCalendar.getInstance();
                                        cal.set(Calendar.SECOND, 0);
                                        cal.set(Calendar.MINUTE, 0);
                                        cal.set(Calendar.HOUR, 0);
                                        cal.set(Calendar.DAY_OF_MONTH, 0);
                                        cal.set(Calendar.MONTH, Integer.parseInt(monthStr) - 1);
                                        cal.set(Calendar.YEAR, Integer.parseInt(yearStr));
                                        time.setCalendar(cal);
                                    }
                                    
                                } else {
                                    Calendar cal = DatatypeConverter.parseDateTime(iso8601time.substring(1));
                                    if (iso8601time.startsWith("-")) {
                                        cal.set(Calendar.ERA, GregorianCalendar.BC);
                                    }
                                    time.setCalendar(cal);
                                }
                                time.setBefore(
                                    Integer.parseInt(valueNode.getAttributes().getNamedItem("before").getNodeValue()));
                                time.setAfter(
                                    Integer.parseInt(valueNode.getAttributes().getNamedItem("after").getNodeValue()));
                                try {
                                    String calendarModel = valueNode.getAttributes().getNamedItem("before").getNodeValue();
                                    time.setCalendarModel(new URL(calendarModel));
                                } catch (Exception e) {
                                }
                                snak.setData(time);
                            }
                            valueNode = valueNode.getNextSibling();
                        }
                    }
                }
                datavalueNode = datavalueNode.getNextSibling();
            }
        } else if ("globe-coordinate".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            while (null != datavalueNode) {
                if ("datavalue".equals(datavalueNode.getNodeName())
                    && "globecoordinate".equals(datavalueNode.getAttributes().getNamedItem("type").getNodeValue())) {
                    Node valueNode = datavalueNode.getFirstChild();
                    while (null != valueNode) {
                        if ("value".equalsIgnoreCase(valueNode.getNodeName())) {
                            GlobeCoordinate coords = new GlobeCoordinate();
                            coords.setLatitude(
                                Double.parseDouble(valueNode.getAttributes().getNamedItem("latitude").getNodeValue()));
                            coords.setLongitude(
                                Double.parseDouble(valueNode.getAttributes().getNamedItem("longitude").getNodeValue()));
                            if (valueNode.getAttributes().getNamedItem("precision") != null) {
                                coords.setPrecision(
                                    Double.parseDouble(valueNode.getAttributes().getNamedItem("precision").getNodeValue()));
                            }
                            String globeUrl = valueNode.getAttributes().getNamedItem("globe").getNodeValue();
                            if (null != globeUrl && globeUrl.startsWith("http://www.wikidata.org/entity/")) {
                                String globeItem = globeUrl.substring("http://www.wikidata.org/entity/".length());
                                coords.setGlobe(new Item(new Entity(globeItem)));
                            }
                            snak.setData(coords);
                        }
                        valueNode = valueNode.getNextSibling();
                    }
                }
                datavalueNode = datavalueNode.getNextSibling();
            }
        } else if ("quantity".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            if ("datavalue".equals(datavalueNode.getNodeName())
                && "quantity".equals(datavalueNode.getAttributes().getNamedItem("type").getNodeValue())) {
                Node valueNode = datavalueNode.getFirstChild();
                while (null != valueNode) {
                    if ("value".equalsIgnoreCase(valueNode.getNodeName())) {
                        Quantity qty = new Quantity();
                        qty.setAmount(Double.parseDouble(valueNode.getAttributes().getNamedItem("amount").getNodeValue()));
                        qty.setUpperBound(
                            Double.parseDouble(valueNode.getAttributes().getNamedItem("upperBound").getNodeValue()));
                        qty.setLowerBound(
                            Double.parseDouble(valueNode.getAttributes().getNamedItem("lowerBound").getNodeValue()));
                        String unitUrl = valueNode.getAttributes().getNamedItem("unit").getNodeValue();
                        if (null != unitUrl && unitUrl.startsWith("http://www.wikidata.org/entity/")) {
                            String unitItem = unitUrl.substring("http://www.wikidata.org/entity/".length());
                            qty.setUnit(new Item(new Entity(unitItem)));
                        }
                        snak.setData(qty);
                    }
                    valueNode = valueNode.getNextSibling();
                }
            }
            datavalueNode = datavalueNode.getNextSibling();
        } else if ("wikibase-property".equalsIgnoreCase(datatype)) {
            Node datavalueNode = snakNode.getFirstChild();
            if ("datavalue".equals(datavalueNode.getNodeName())
                && "wikibase-entityid".equals(datavalueNode.getAttributes().getNamedItem("type").getNodeValue())) {
                Node valueNode = datavalueNode.getFirstChild();
                while (null != valueNode) {
                    if ("value".equalsIgnoreCase(valueNode.getNodeName())) {
                        snak.setData(
                            new Property("P" + valueNode.getAttributes().getNamedItem("numeric-id").getNodeValue()));
                    }
                    valueNode = valueNode.getNextSibling();
                }
            }
            datavalueNode = datavalueNode.getNextSibling();
        }
        return snak;
    }
}

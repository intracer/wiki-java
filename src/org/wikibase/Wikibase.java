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

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.wikibase.data.*;
import org.wikipedia.Wiki;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class Wikibase extends Wiki {

    public Wikibase(String url) {
        super(url);
    }

    public Wikibase() {
        this("www.wikidata.org");
    }

    private String encode(String text) throws IOException {
        return encode(text, false);
    }

    /**
     * Returns an entity identified by the title of a wiki page.
     * 
     * @param site
     * @param title page title
     * @return Wikibase entity
     * @throws IOException
     * @throws WikibaseException
     */
    public Entity getWikibaseItemBySiteAndTitle(final String site, final String title)
        throws IOException, WikibaseException {
        String url = query + "action=wbgetentities" +
                "&sites=" + site +
                "&titles=" + encode(title);

        final String text = fetch(url, "getWikibaseItem");

        return WikibaseEntityFactory.getWikibaseItem(text);
    }

    /**
     * Returns the entity taken as a parameter, populated with data from wikibase. Use this when you have the Entity object
     * that only contains the ID, which will happen if this entity is reached via another entity's property.
     * 
     * @param item
     * @return
     * @throws IOException
     * @throws WikibaseException
     */
    public Entity getWikibaseItemById(final Entity item) throws IOException, WikibaseException {
        return getWikibaseItemById(item.getId());
    }

    /**
     * Returns the entity associated with the specified wikibase id.
     * 
     * @return
     * @throws IOException
     * @throws WikibaseException
     */
    public Entity getWikibaseItemById(final String id) throws IOException, WikibaseException {
        if (!Pattern.matches("[Qq]\\d+", id)) {
                throw new WikibaseException(id + " is not a valid Wikibase id");
            }
        String url = query + "action=wbgetentities" +
                "&ids=" + id +
                "&format=xml";

        final String text = fetch(url, "getWikibaseItem");

        return WikibaseEntityFactory.getWikibaseItem(text);
    }

    /**
     * Retrieves the title of the corresponding page in another site.
     * 
     * @param site
     * @param pageName
     * @param language
     * @return
     * @throws WikibaseException
     * @throws IOException
     */
    public String getTitleInLanguage(final String site, final String pageName, final String language)
        throws WikibaseException, IOException {
        Entity ent = getWikibaseItemBySiteAndTitle(site, pageName);
        return ent.getSitelinks().get(language).getPageName();
    }

    /**
     * Links two pages from different sites via wikibase.
     * 
     * @param fromSite
     * @param fromTitle
     * @param toSite
     * @param toTitle
     * @throws IOException
     */
    public void linkPages(final String fromSite, final String fromTitle, final String toSite, final String toTitle)
        throws IOException {
        String url = query + "action=wbgetentities" +
                "&sites=" + toSite +
                "&titles=" + encode(toTitle) +
                "&format=xml";
        final String text = fetch(url, "linkPages");

        final int startIndex = text.indexOf("<entity");
        final int endIndex = text.indexOf(">", startIndex);
        final String entityTag = text.substring(startIndex, endIndex);
        final StringTokenizer entityTok = new StringTokenizer(entityTag, " ", false);
        String q = null;
        while (entityTok.hasMoreTokens()) {
            final String entityAttr = entityTok.nextToken();
            if (!entityAttr.contains("=")) {
                continue;
            }
            final String[] entityParts = entityAttr.split("\\=");
            if (entityParts[0].trim().startsWith("title")) {
                q = entityParts[1].trim().replace("\"", "");
            }
        }

        if (null == q) {
            return;
        }

        String getTokenURL = query + "prop=info" +
                "&intoken=edit" +
                "&titles=" + encode(q) +
                "&format=xml";
        String res = fetch(getTokenURL, "linkPages");

        final int pageStartIndex = res.indexOf("<page ");
        final int pageEndIndex = res.indexOf(">", pageStartIndex);
        final String pageTag = res.substring(pageStartIndex, pageEndIndex);

        String editToken = null;
        final StringTokenizer pageTok = new StringTokenizer(pageTag, " ", false);
        while (pageTok.hasMoreTokens()) {
            final String pageAttr = pageTok.nextToken();
            if (!pageAttr.contains("=")) {
                continue;
            }
            final String[] entityParts = pageAttr.split("=");
            if (entityParts[0].trim().startsWith("edittoken")) {
                editToken = entityParts[1].trim().replace("\"", "");
            }
        }
        String postData = ("&tosite=" + toSite) +
                "&totitle=" + encode(toTitle) +
                "&fromtitle=" + encode(fromTitle) +
                "&fromsite=" + fromSite +
                "&token=" + encode(editToken);

        post(query + "action=wblinktitles", postData, "linkPages");
    }

    public String createItem(Entity createdEntity) throws IOException, WikibaseException {
        String edittoken = obtainToken();

        String url = query + "action=wbeditentity" +
                "&new=item";
        String postdata = ("&data=" + encode(createdEntity.toJSON())) +
                "&clear=yes" +
                "&token=" + encode(edittoken, false) +
                "&format=xml";
        String text1 = post(url, postdata, "createItem");
        String ret = null;

        DocumentBuilderFactory domBuilderFactory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = domBuilderFactory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(text1.getBytes()));
            XPathFactory xpathFactory = XPathFactory.newInstance();
            XPath xPath = xpathFactory.newXPath();
            XPathExpression apiExpression = xPath.compile("/api[1]");
            Node apiNode = (Node) apiExpression.evaluate(document, XPathConstants.NODE);
            if (null == apiNode || null == apiNode.getAttributes()
                || null == apiNode.getAttributes().getNamedItem("success")) {
                throw new WikibaseException("API root node with success parameter not found in text.");
            }
            if ("1".equals(apiNode.getAttributes().getNamedItem("success").getNodeValue())) {
                XPathExpression entityExpression = xPath.compile("/api[1]/entity[1]");
                Node entityNode = (Node) entityExpression.evaluate(document, XPathConstants.NODE);
                if (null == entityNode || null == entityNode.getAttributes()
                    || null == entityNode.getAttributes().getNamedItem("id")) {
                    throw new WikibaseException("Entity node not present or without id attribute");
                }
                ret = entityNode.getAttributes().getNamedItem("id").getNodeValue();
            } else {
                XPathExpression errorExpression = xPath.compile("/api[1]/error[1]");
                Node errorNode = (Node) errorExpression.evaluate(document, XPathConstants.NODE);
                if (null != errorNode && null != errorNode.getAttributes() && null != errorNode.getAttributes().getNamedItem("info")) {
                    throw new WikibaseException(errorNode.getAttributes().getNamedItem("info").getNodeValue());
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "createItem", e.getMessage());
            return null;
        }
        return ret;
    }

    /**
     * Adds specified claim to the entity with the specified ID
     * 
     * @param entityId
     * @param claim
     * @return the guid of the created claim
     * @throws WikibaseException
     * @throws IOException
     */
    public String addClaim(String entityId, Claim claim) throws WikibaseException, IOException {
        String edittoken = obtainToken();

        String url = query + "action=wbcreateclaim" +
                "&entity=" + (entityId.startsWith("Q") ? entityId : ("Q" + entityId));

        String postdata = "&snaktype=value" +
                "&property=" + claim.getProperty().getId() +
                "&value=" + encode(claim.getValue().valueToJSON()) +
                "&token=" + encode(edittoken) +
                "&format=xml";
        String method = "addClaim";
        String text1 = post(url, postdata, method);

        DocumentBuilderFactory domBuilderFactory = DocumentBuilderFactory.newInstance();
        String ret = null;
        try {
            DocumentBuilder builder = domBuilderFactory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(text1.getBytes()));
            XPathFactory xpathFactory = XPathFactory.newInstance();
            XPath xPath = xpathFactory.newXPath();
            XPathExpression apiExpression = xPath.compile("/api[1]");
            Node apiNode = (Node) apiExpression.evaluate(document, XPathConstants.NODE);
            if (null == apiNode || null == apiNode.getAttributes()
                || null == apiNode.getAttributes().getNamedItem("success")) {
                throw new WikibaseException("API root node with success parameter not found in text.");
            }
            if ("1".equals(apiNode.getAttributes().getNamedItem("success").getNodeValue())) {
                XPathExpression claimExpression = xPath.compile(
                    "/api[1]/claim[1]");
                Node claimNode = (Node) claimExpression.evaluate(document, XPathConstants.NODE);
                if (null == claimNode || null == claimNode.getAttributes()
                    || null == claimNode.getAttributes().getNamedItem("id")) {
                    throw new WikibaseException("Claim node not present or without id attribute");
                }
                ret = claimNode.getAttributes().getNamedItem("id").getNodeValue();
            } else {
                XPathExpression errorExpression = xPath.compile("/api[1]/error[1]");
                Node errorNode = (Node) errorExpression.evaluate(document, XPathConstants.NODE);
                if (null != errorNode && null != errorNode.getAttributes() && null != errorNode.getAttributes().getNamedItem("info")) {
                    throw new WikibaseException(errorNode.getAttributes().getNamedItem("info").getNodeValue());
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, method, e.getMessage());
            return null;
        }
        return ret;
    }

    /**
     * Edits the specified claim by replacing it with the new one
     * 
     * @param claim
     * @return the guid of the created claim
     * @throws WikibaseException
     * @throws IOException
     */
    public String editClaim(Claim claim) throws WikibaseException, IOException {
        String edittoken = obtainToken();

        String postdata = "&claim=" + encode(claim.toJSON()) +
                "&token=" + encode(edittoken);

        String method = "editClaim";
        String text1 = post(query + "action=wbsetclaim", postdata, method);

        DocumentBuilderFactory domBuilderFactory = DocumentBuilderFactory.newInstance();
        String ret = null;
        try {
            DocumentBuilder builder = domBuilderFactory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(text1.getBytes()));
            XPathFactory xpathFactory = XPathFactory.newInstance();
            XPath xPath = xpathFactory.newXPath();
            XPathExpression apiExpression = xPath.compile("/api[1]");
            Node apiNode = (Node) apiExpression.evaluate(document, XPathConstants.NODE);
            if (null == apiNode || null == apiNode.getAttributes()
                || null == apiNode.getAttributes().getNamedItem("success")) {
                throw new WikibaseException("API root node with success parameter not found in text.");
            }
            if ("1".equals(apiNode.getAttributes().getNamedItem("success").getNodeValue())) {
                XPathExpression claimExpression = xPath.compile(
                    "/api[1]/claim[1]");
                Node claimNode = (Node) claimExpression.evaluate(document, XPathConstants.NODE);
                if (null == claimNode || null == claimNode.getAttributes()
                    || null == claimNode.getAttributes().getNamedItem("id")) {
                    throw new WikibaseException("Claim node not present or without id attribute");
                }
                ret = claimNode.getAttributes().getNamedItem("id").getNodeValue();
            } else {
                XPathExpression errorExpression = xPath.compile("/api[1]/error[1]");
                Node errorNode = (Node) errorExpression.evaluate(document, XPathConstants.NODE);
                if (null != errorNode && null != errorNode.getAttributes() && null != errorNode.getAttributes().getNamedItem("info")) {
                    throw new WikibaseException(errorNode.getAttributes().getNamedItem("info").getNodeValue());
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, method, e.getMessage());
            return null;
        }
        return ret;
    }
    /**
     * Removes the claim with the specified id from the entity with the specified ID
     * 
     * @param claimId
     * @return the guid of the created claim
     * @throws WikibaseException
     * @throws IOException
     */
    public String removeClaim(String claimId) throws WikibaseException, IOException {
        String edittoken = obtainToken();

        String url = query + "action=wbremoveclaims";

        String postdata = ("&claim=" + claimId) +
                "&token=" + encode(edittoken) +
                "&format=xml";
        String text1 = post(url, postdata, "removeClaim");

        DocumentBuilderFactory domBuilderFactory = DocumentBuilderFactory.newInstance();
        String ret = null;
        try {
            DocumentBuilder builder = domBuilderFactory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(text1.getBytes()));
            XPathFactory xpathFactory = XPathFactory.newInstance();
            XPath xPath = xpathFactory.newXPath();
            XPathExpression apiExpression = xPath.compile("/api[1]");
            Node apiNode = (Node) apiExpression.evaluate(document, XPathConstants.NODE);
            if (null == apiNode || null == apiNode.getAttributes()
                || null == apiNode.getAttributes().getNamedItem("success")) {
                throw new WikibaseException("API root node with success parameter not found in text.");
            }
            if (!"1".equals(apiNode.getAttributes().getNamedItem("success").getNodeValue())) {
                XPathExpression errorExpression = xPath.compile("/api[1]/error[1]");
                Node errorNode = (Node) errorExpression.evaluate(document, XPathConstants.NODE);
                if (null != errorNode && null != errorNode.getAttributes() && null != errorNode.getAttributes().getNamedItem("info")) {
                    throw new WikibaseException(errorNode.getAttributes().getNamedItem("info").getNodeValue());
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "removeClaim", e.getMessage());
            return null;
        }
        return ret;
    }
    public String addQualifier(String claimGUID, String propertyId, WikibaseData qualifier) throws WikibaseException, IOException {
        String edittoken = obtainToken();

        String url = query + "action=wbsetqualifier" +
                "&claim=" + claimGUID +
                "&property=" + propertyId.toUpperCase() +
                "&snaktype=value";

        String postdata = "&value=" + encode(qualifier.valueToJSON()) +
                "&token=" + encode(edittoken);

        String text1 = post(url, postdata, "addQualifier");
        log(Level.INFO, "addQualifier", text1);
        return null;
    }
    
    public String addReference(String claimGUID, List<Snak> ref) throws IOException, WikibaseException {
        String edittoken = obtainToken();

        String url = query + "action=wbsetreference" +
                "&statement=" + claimGUID;

        StringBuilder snakBuilder = new StringBuilder("{");
        boolean refStarted = false;
        Map<Property, List<Snak>> referenceMap = new HashMap<Property, List<Snak>>();
        for (Snak eachSnak: ref) {
            List<Snak> claimList = referenceMap.get(eachSnak.getProperty());
            if (null == claimList) {
                claimList = new ArrayList<>();
            }
            claimList.add(eachSnak);
            referenceMap.put(eachSnak.getProperty(), claimList);
        }
        
        for (Entry<Property, List<Snak>> eachRefEntry: referenceMap.entrySet()) {
            if (refStarted) {
                snakBuilder.append(',');
            }
            snakBuilder.append('\"').append(eachRefEntry.getKey().getId()).append("\":");
            snakBuilder.append('[');
            boolean entryStarted = false;
            for (Snak eachSnak: eachRefEntry.getValue()) {
                if (entryStarted) {
                    snakBuilder.append(',');
                }
                snakBuilder.append(eachSnak.toJSON());
                entryStarted = true;
            }
            snakBuilder.append(']');
            refStarted = true;
        }
        snakBuilder.append('}');

        String postdata = ("&snaks=" + encode(snakBuilder.toString())) +
                "&token=" + encode(edittoken);
        String text1 = post(url, postdata, "addReference");
        log(Level.INFO, "addReference", text1);
        return null;
    }

    public void setLabel(String qid, String language, String label) throws IOException, WikibaseException {
        String token = obtainToken();

        String url = query + "action=wbsetlabel" +
                "&id=" + qid;

        String postdata = ("&language=" + language) +
                "&value=" + encode(label) +
                "&token=" + encode(token);

        String text1 = post(url, postdata, "setLabel");
        log(Level.INFO, "setLabel", text1);
    }
    
    public void setDescription(String qid, String language, String label) throws IOException, WikibaseException {
        String token = obtainToken();
        String url = query + "action=wbsetdescription" +
                "&id=" + qid;

        String postdata = ("&language=" + language) +
                "&value=" + encode(label) +
                "&token=" + encode(token) +
                "&format=xml";

        String text1 = post(url, postdata, "setDescription");
        log(Level.INFO, "setDescription", text1);
    }
    
    private String obtainToken() throws IOException, WikibaseException {
        String url1 = query + "action=query" +
                "&meta=tokens";

        String text = fetch(url1, "obtainToken");

        DocumentBuilderFactory domBuilderFactory = DocumentBuilderFactory.newInstance();
        Node tokenNode;
        try {
            DocumentBuilder builder = domBuilderFactory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(text.getBytes()));
            XPathFactory xpathFactory = XPathFactory.newInstance();
            XPath xPath = xpathFactory.newXPath();
            XPathExpression apiExpression = xPath.compile("/api[1]");
            Node apiNode = (Node) apiExpression.evaluate(document, XPathConstants.NODE);
            if (null == apiNode) {
                throw new WikibaseException("API root node not found in text.");
            }
            XPathExpression tokenExpression = xPath.compile("/api[1]/query[1]/tokens[1]");
            tokenNode = (Node) tokenExpression.evaluate(document, XPathConstants.NODE);
        } catch (Exception e) {
            throw new WikibaseException(e);
        }
        if (null == tokenNode || tokenNode.getAttributes() == null
            || tokenNode.getAttributes().getNamedItem("csrftoken") == null) {
            throw new WikibaseException("Token node not found");
        }
        return tokenNode.getAttributes().getNamedItem("csrftoken").getNodeValue();
    }
}

// Copyright 2009 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.pusher;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.enterprise.connector.common.AlternateContentFilterInputStream;
import com.google.enterprise.connector.common.BigEmptyDocumentFilterInputStream;
import com.google.enterprise.connector.common.CompressedFilterInputStream;
import com.google.enterprise.connector.manager.Context;
import com.google.enterprise.connector.servlet.ServletUtil;
import com.google.enterprise.connector.spi.Document;
import com.google.enterprise.connector.spi.Principal;
import com.google.enterprise.connector.spi.Property;
import com.google.enterprise.connector.spi.RepositoryDocumentException;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SimpleProperty;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.SpiConstants.AclAccess;
import com.google.enterprise.connector.spi.SpiConstants.AclScope;
import com.google.enterprise.connector.spi.SpiConstants.ContentEncoding;
import com.google.enterprise.connector.spi.SpiConstants.DocumentType;
import com.google.enterprise.connector.spi.SpiConstants.FeedType;
import com.google.enterprise.connector.spi.Value;
import com.google.enterprise.connector.spi.XmlUtils;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;
import com.google.enterprise.connector.spiimpl.PrincipalValue;
import com.google.enterprise.connector.spiimpl.ValueImpl;
import com.google.enterprise.connector.traversal.FileSizeLimitInfo;
import com.google.enterprise.connector.util.UniqueIdGenerator;
import com.google.enterprise.connector.util.UuidGenerator;
import com.google.enterprise.connector.util.Base64FilterInputStream;
import com.google.enterprise.connector.util.filter.AbstractDocumentFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class to generate XML Feed for a document from the Document and send it
 * to GSA.
 */
public class XmlFeed extends ByteArrayOutputStream implements FeedData {
  private static final Logger LOGGER =
      Logger.getLogger(XmlFeed.class.getName());

  private final String dataSource;
  private final FeedType feedType;
  private final UrlConstructor urlConstructor;
  private final int maxFeedSize;
  private final Appendable feedLogBuilder;
  private final String feedId;
  private final FileSizeLimitInfo fileSizeLimit;
  private final AclTransformFilter aclTransformFilter;

  /** Encoding method to use for Document content. */
  private final ContentEncoding contentEncoding;

  /** If true, ACLs support inheritance and deny; otherwise legacy ACLs. */
  private final boolean supportsInheritedAcls;

  private static UniqueIdGenerator uniqueIdGenerator = new UuidGenerator();

  private static StripAclDocumentFilter stripAclDocumentFilter =
      new StripAclDocumentFilter();
  private static ExtractedAclDocumentFilter extractedAclDocumentFilter =
      new ExtractedAclDocumentFilter();
  private static InheritFromExtractedAclDocumentFilter
      inheritFromExtractedAclDocumentFilter =
      new InheritFromExtractedAclDocumentFilter();

  private boolean isClosed;
  private int recordCount;

  public static final Set<String> propertySkipSet = ImmutableSet.<String>of(
      // TODO: What about displayurl, ispublic, searchurl? Should we
      // have an explicit opt-in list of google: properties instead an
      // opt-out list?
      SpiConstants.PROPNAME_ACLINHERITFROM_DOCID,
      SpiConstants.PROPNAME_ACLINHERITFROM_FEEDTYPE,
      SpiConstants.PROPNAME_ACTION,
      SpiConstants.PROPNAME_AUTHMETHOD,
      SpiConstants.PROPNAME_CONTENT,
      SpiConstants.PROPNAME_CONTENTURL,
      SpiConstants.PROPNAME_CONTENT_ENCODING,
      SpiConstants.PROPNAME_CONTENT_LENGTH,
      SpiConstants.PROPNAME_DOCID,
      SpiConstants.PROPNAME_DOCUMENTTYPE,
      SpiConstants.PROPNAME_FEEDTYPE,
      SpiConstants.PROPNAME_LOCK,
      SpiConstants.PROPNAME_OVERWRITEACLS,
      SpiConstants.PROPNAME_PAGERANK,
      SpiConstants.PROPNAME_SECURITYTOKEN);

  // Strings for XML tags.
  public static final String XML_DEFAULT_ENCODING = "UTF-8";
  private static final String XML_START = "<?xml version='1.0' encoding='"
      + XML_DEFAULT_ENCODING + "'?><!DOCTYPE gsafeed PUBLIC"
      + " \"-//Google//DTD GSA Feeds//EN\" \"gsafeed.dtd\">";
  private static final String XML_GSAFEED = "gsafeed";
  private static final String XML_HEADER = "header";
  private static final String XML_DATASOURCE = "datasource";
  private static final String XML_FEEDTYPE = "feedtype";
  private static final String XML_GROUP = "group";
  private static final String XML_RECORD = "record";
  private static final String XML_METADATA = "metadata";
  private static final String XML_META = "meta";
  private static final String XML_CONTENT = "content";
  private static final String XML_ACTION = "action";
  private static final String XML_URL = "url";
  private static final String XML_DISPLAY_URL = "displayurl";
  private static final String XML_MIMETYPE = "mimetype";
  private static final String XML_LAST_MODIFIED = "last-modified";
  private static final String XML_LOCK = "lock";
  private static final String XML_AUTHMETHOD = "authmethod";
  private static final String XML_PAGERANK = "pagerank";
  private static final String XML_NAME = "name";
  private static final String XML_ENCODING = "encoding";
  private static final String XML_OVERWRITEACLS = "overwrite-acls";

  private static final String CONNECTOR_AUTHMETHOD = "httpbasic";

  private static final String XML_ACL = "acl";
  private static final String XML_TYPE = "inheritance-type";
  private static final String XML_INHERIT_FROM = "inherit-from";
  private static final String XML_PRINCIPAL = "principal";
  private static final String XML_SCOPE = "scope";
  private static final String XML_ACCESS = "access";

  private final String  supportedEncodings;

  public XmlFeed(String dataSource, FeedType feedType, 
      FileSizeLimitInfo fileSizeLimit, Appendable feedLogBuilder,
      FeedConnection feedConnection) throws IOException {
    super((int) fileSizeLimit.maxFeedSize());
    this.maxFeedSize = (int) fileSizeLimit.maxFeedSize();
    this.dataSource = dataSource;
    this.feedType = feedType;
    this.fileSizeLimit = fileSizeLimit;
    this.feedLogBuilder = feedLogBuilder;
    this.recordCount = 0;
    this.isClosed = false;
    this.feedId = uniqueIdGenerator.uniqueId();
    this.supportsInheritedAcls = feedConnection.supportsInheritedAcls();

    // Configure the dynamic ACL transformation filters for the documents.
    this.urlConstructor = new UrlConstructor(dataSource, feedType);
    this.aclTransformFilter =
        new AclTransformFilter(feedConnection, this.urlConstructor);

    supportedEncodings = feedConnection.getContentEncodings().toLowerCase();
    // Check to see if the GSA supports compressed content feeds.
    this.contentEncoding = (
        supportedEncodings.indexOf(ContentEncoding.BASE64COMPRESSED.toString())
        >= 0) ? ContentEncoding.BASE64COMPRESSED : ContentEncoding.BASE64BINARY;

    String prefix = xmlFeedPrefix(dataSource, feedType);
    write(prefix.getBytes(XML_DEFAULT_ENCODING));
  }

  @VisibleForTesting
  static void setUniqueIdGenerator(UniqueIdGenerator idGenerator) {
    uniqueIdGenerator = idGenerator;
  }

  /*
   * XmlFeed Public Interface.
   */

  /**
   * Returns the unique ID assigned to this Feed file and all
   * records within the Feed.
   */
  public String getFeedId() {
    return feedId;
  }

  /**
   * Returns {@code true} if the feed is sufficiently full to submit
   * to the GSA.
   */
  public boolean isFull() {
    int bytesLeft = maxFeedSize - size();
    int avgRecordSize = size()/recordCount;
    // If less then 3 average size docs would fit, then consider it full.
    if (bytesLeft < (3 * avgRecordSize)) {
      return true;
    } else if (bytesLeft > (10 * avgRecordSize)) {
      return false;
    } else {
      // If its more 90% full, then consider it full.
      return (bytesLeft < (maxFeedSize / 10));
    }
  }

  /**
   * Set the count of records in this feed.
   */
  public synchronized void setRecordCount(int count) {
    recordCount = count;
  }

  /**
   * Return the count of records in this feed.
   */
  public synchronized int getRecordCount() {
    return recordCount;
  }

  /**
   * Add the XML record for a given document to the Feed.
   */
  public synchronized void addRecord(Document document)
      throws RepositoryException, IOException {
    // Apply any ACL transformations to the document.
    // Build an XML feed record for the document.
    xmlWrapRecord(aclTransformFilter.newDocumentFilter(document));
  }

  /*
   * FeedData Interface.
   */

  /**
   * Return the {@link FeedType} for all records in this Feed.
   */
  public FeedType getFeedType() {
    return feedType;
  }

  /**
   * Return the data source for all records in this Feed.
   */
  public String getDataSource() {
    return dataSource;
  }

  /*
   * ByteArrayOutputStream (and related) Interface.
   */

  /**
   * Resets the size of this ByteArrayOutputStream to the
   * specified {@code size}, effectively discarding any
   * data that may have been written passed that point.
   * Like {@code reset()}, this method retains the previously
   * allocated buffer.
   * <p>
   * This method may be used to reduce the size of the data stored,
   * but not to increase it.  In other words, the specified {@code size}
   * cannot be greater than the current size.
   *
   * @param size new data size.
   */
  public synchronized void reset(int size) {
    if (size < 0 || size > count) {
      throw new IllegalArgumentException(
          "New size must not be negative or greater than the current size.");
    }
    count = size;
  }

  /**
   * Reads the complete contents of the supplied InputStream
   * directly into buffer of this ByteArrayOutputStream.
   * This avoids the data copy that would occur if using
   * {@code InputStream.read(byte[], int, int)}, followed by
   * {@code ByteArrayOutputStream.write(byte[], int, int)}.
   *
   * @param in the InputStream from which to read the data.
   * @throws IOException if an I/O error occurs.
   */
  public synchronized void readFrom(InputStream in) throws IOException {
    int bytes = 0;
    do {
      count += bytes;
      if (count >= buf.length) {
        // Need to grow buffer.
        int incr = Math.min(buf.length, 8 * 1024 * 1024);
        byte[] newbuf = new byte[buf.length + incr];
        System.arraycopy(buf, 0, newbuf, 0, buf.length);
        buf = newbuf;
      }
      bytes = in.read(buf, count, buf.length - count);
    } while (bytes != -1);
  }

  @Override
  public synchronized void close() throws IOException {
    if (!isClosed) {
      isClosed = true;
      String suffix = xmlFeedSuffix();
      write(suffix.getBytes(XML_DEFAULT_ENCODING));
    }
  }

  /*
   * Private Methods to XML encode the feed data.
   */

  /**
   * Construct the XML header for a feed file.
   *
   * @param dataSource The dataSource for the feed.
   * @param feedType The type of feed.
   * @return XML feed header string.
   */
  private static String xmlFeedPrefix(String dataSource, FeedType feedType) {
    // Build prefix.
    StringBuffer prefix = new StringBuffer();
    prefix.append(XML_START).append('\n');
    prefix.append(XmlUtils.xmlWrapStart(XML_GSAFEED)).append('\n');
    prefix.append(XmlUtils.xmlWrapStart(XML_HEADER)).append('\n');
    prefix.append(XmlUtils.xmlWrapStart(XML_DATASOURCE));
    prefix.append(dataSource);
    prefix.append(XmlUtils.xmlWrapEnd(XML_DATASOURCE));
    prefix.append(XmlUtils.xmlWrapStart(XML_FEEDTYPE));
    prefix.append(feedType.toLegacyString());
    prefix.append(XmlUtils.xmlWrapEnd(XML_FEEDTYPE));
    prefix.append(XmlUtils.xmlWrapEnd(XML_HEADER));
    prefix.append(XmlUtils.xmlWrapStart(XML_GROUP)).append('\n');
    return prefix.toString();
  }

  /**
   * Construct the XML footer for a feed file.
   *
   * @return XML feed suffix string.
   */
  private static String xmlFeedSuffix() {
    // Build suffix.
    StringBuffer suffix = new StringBuffer();
    suffix.append(XmlUtils.xmlWrapEnd(XML_GROUP));
    suffix.append(XmlUtils.xmlWrapEnd(XML_GSAFEED));
    return suffix.toString();
  }

  /*
   * Generate the record tag for the xml data.
   *
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private void xmlWrapRecord(Document document)
      throws RepositoryException, IOException {
    if (supportsInheritedAcls) {
      String docType = DocUtils.getOptionalString(document,
          SpiConstants.PROPNAME_DOCUMENTTYPE);
      if (docType != null
          && DocumentType.findDocumentType(docType) == DocumentType.ACL) {
        xmlWrapAclRecord(document);
        recordCount++;
        return;
      } else if (feedType == FeedType.CONTENTURL
                 && DocUtils.hasAclProperties(document)) {
        // GSA 7.0 does not support case-sensitivity or namespaces in ACLs
        // during crawl-time. So we have to send the ACLs at feed-time.
        // But the crawl-time metadata overwrites the feed-time ACLs.
        // The proposed escape is to send a named resource ACL in the feed for
        // each document, and at crawl-time return an empty ACL that inherits
        // from the corresponding named resource ACL.
        xmlWrapAclRecord(
            extractedAclDocumentFilter.newDocumentFilter(document));
        recordCount++;
        document =
            inheritFromExtractedAclDocumentFilter.newDocumentFilter(document);
      }
    }
    xmlWrapDocumentRecord(document);
    recordCount++;
  }

  /*
   * Generate the record tag for the xml data.
   *
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private void xmlWrapDocumentRecord(Document document)
      throws RepositoryException, IOException {
    boolean aclRecordAllowed = supportsInheritedAcls;
    boolean metadataAllowed = (feedType != FeedType.CONTENTURL);
    boolean contentAllowed = (feedType == FeedType.CONTENT);

    StringBuilder prefix = new StringBuilder();
    prefix.append("<").append(XML_RECORD);

    String searchUrl =
        urlConstructor.getRecordUrl(document, DocumentType.RECORD);
    XmlUtils.xmlAppendAttr(XML_URL, searchUrl, prefix);

    String displayUrl = DocUtils.getOptionalString(document,
        SpiConstants.PROPNAME_DISPLAYURL);
    XmlUtils.xmlAppendAttr(XML_DISPLAY_URL, displayUrl, prefix);

    ActionType actionType = null;
    String action = DocUtils.getOptionalString(document,
        SpiConstants.PROPNAME_ACTION);
    if (action != null) {
      // Compare to legal action types.
      actionType = ActionType.findActionType(action);
      if (actionType == ActionType.ADD) {
        XmlUtils.xmlAppendAttr(XML_ACTION, actionType.toString(), prefix);
      } else if (actionType == ActionType.DELETE) {
        XmlUtils.xmlAppendAttr(XML_ACTION, actionType.toString(), prefix);
        aclRecordAllowed = false;
        metadataAllowed = false;
        contentAllowed = false;
      } else if (actionType == ActionType.ERROR) {
        LOGGER.log(Level.WARNING, "Illegal tag used for ActionType: " + action);
        actionType = null;
      }
    }

    boolean lock = DocUtils.getOptionalBoolean(document, SpiConstants.PROPNAME_LOCK, false);
    if (lock) {
      XmlUtils.xmlAppendAttr(XML_LOCK, Value.getBooleanValue(true).toString(), prefix);
    }

    // Do not validate the values, just send them in the feed.
    String pagerank =
        DocUtils.getOptionalString(document, SpiConstants.PROPNAME_PAGERANK);
    XmlUtils.xmlAppendAttr(XML_PAGERANK, pagerank, prefix);

    String mimetype =
        DocUtils.getOptionalString(document, SpiConstants.PROPNAME_MIMETYPE);
    if (mimetype == null) {
      mimetype = SpiConstants.DEFAULT_MIMETYPE;
    }
    XmlUtils.xmlAppendAttr(XML_MIMETYPE, mimetype, prefix);

    try {
      String lastModified = DocUtils.getCalendarAndThrow(document,
          SpiConstants.PROPNAME_LASTMODIFIED);
      if (lastModified == null) {
        LOGGER.log(Level.FINEST, "Document does not contain "
            + SpiConstants.PROPNAME_LASTMODIFIED);
      } else {
        XmlUtils.xmlAppendAttr(XML_LAST_MODIFIED, lastModified, prefix);
      }
    } catch (IllegalArgumentException e) {
      LOGGER.log(Level.WARNING, "Swallowing exception while getting "
          + SpiConstants.PROPNAME_LASTMODIFIED, e);
    }

    try {
      ValueImpl v = (ValueImpl) Value.getSingleValue(document,
          SpiConstants.PROPNAME_ISPUBLIC);
      if (v != null) {
        boolean isPublic = v.toBoolean();
        if (!isPublic) {
          String authmethod = DocUtils.getOptionalString(document,
              SpiConstants.PROPNAME_AUTHMETHOD);
          if (Strings.isNullOrEmpty(authmethod)){
            authmethod = CONNECTOR_AUTHMETHOD;
          }
          XmlUtils.xmlAppendAttr(XML_AUTHMETHOD, authmethod, prefix);
        }
      }
    } catch (IllegalArgumentException e) {
      LOGGER.log(Level.WARNING, "Illegal value for ispublic property."
          + " Treat as a public doc", e);
    }

    prefix.append(">\n");

    if (aclRecordAllowed && DocUtils.hasAclProperties(document)) {
      xmlWrapAclRecord(prefix, document);
      // Prevent ACLs from showing up in metadata.
      document = stripAclDocumentFilter.newDocumentFilter(document);
    }
    if (metadataAllowed) {
      xmlWrapMetadata(prefix, document);
    }

    StringBuilder suffix = new StringBuilder();
    ContentEncoding documentContentEncoding = null;
    ContentEncoding alternateEncoding = null;
    if (contentAllowed) {
      // Determine the content encoding to specify.
      String documentContentEncodingStr = DocUtils.getOptionalString(
          document, SpiConstants.PROPNAME_CONTENT_ENCODING);

      if (!Strings.isNullOrEmpty(documentContentEncodingStr)) {
        documentContentEncoding =
            ContentEncoding.findContentEncoding(documentContentEncodingStr);
        if (documentContentEncoding == ContentEncoding.ERROR
            || supportedEncodings.indexOf(documentContentEncodingStr) < 0) {
          String message =
              "Unsupported content encoding: " + documentContentEncodingStr;
          LOGGER.log(Level.WARNING, message);
          throw new RepositoryDocumentException(message);
        }
      }
      alternateEncoding = (documentContentEncoding == null) 
          ? contentEncoding : documentContentEncoding;
  
      // If including document content, wrap it with <content> tags.
      prefix.append("<");
      prefix.append(XML_CONTENT);
      XmlUtils.xmlAppendAttr(XML_ENCODING,
          alternateEncoding.toString(), prefix);
      prefix.append(">\n");

      suffix.append('\n');
      XmlUtils.xmlAppendEndTag(XML_CONTENT, suffix);
    }

    write(prefix.toString().getBytes(XML_DEFAULT_ENCODING));

    if (contentAllowed) {
      InputStream contentStream = getContentStream(
          document, documentContentEncoding, alternateEncoding);
      try {
        readFrom(contentStream);
      } finally {
        contentStream.close();
      }
    }

    XmlUtils.xmlAppendEndTag(XML_RECORD, suffix);
    write(suffix.toString().getBytes(XML_DEFAULT_ENCODING));

    if (feedLogBuilder != null) {
      try {
        feedLogBuilder.append(prefix);
        if (contentAllowed) {
          feedLogBuilder.append("...content...");
        }
        feedLogBuilder.append(suffix);
      } catch (IOException e) {
        // This won't happen with StringBuffer or StringBuilder.
        LOGGER.log(Level.WARNING, "Exception while constructing feed log:", e);
      }
    }
  }

  /*
   * Generate the record tag for the ACL xml data, appending to {@code aclBuff}.
   */
  private void xmlWrapAclRecord(StringBuilder aclBuff, Document acl)
      throws IOException, RepositoryException {
    aclBuff.append("<").append(XML_ACL);
    String docType = DocUtils.getOptionalString(acl,
        SpiConstants.PROPNAME_DOCUMENTTYPE);
    if (docType != null
        && DocumentType.findDocumentType(docType) == DocumentType.ACL) {
      // Only specify the URL if this is a stand-alone ACL.
      XmlUtils.xmlAppendAttr(XML_URL, 
         urlConstructor.getRecordUrl(acl, DocumentType.ACL), aclBuff);
    }

    String inheritanceType = DocUtils.getOptionalString(acl,
        SpiConstants.PROPNAME_ACLINHERITANCETYPE);
    if (!Strings.isNullOrEmpty(inheritanceType)) {
      XmlUtils.xmlAppendAttr(XML_TYPE, inheritanceType, aclBuff);
    }

    String inheritFrom = urlConstructor.getInheritFromUrl(acl);
    if (!Strings.isNullOrEmpty(inheritFrom)) {
      XmlUtils.xmlAppendAttr(XML_INHERIT_FROM, inheritFrom, aclBuff);
    }
    aclBuff.append(">\n");

    // add principal info
    getPrincipalXml(acl, aclBuff);
    XmlUtils.xmlAppendEndTag(XML_ACL, aclBuff);
  }

  /*
   * Generate the record tag for the ACL xml data.
   */
  private void xmlWrapAclRecord(Document acl) throws IOException,
      RepositoryException {
    StringBuilder aclBuff = new StringBuilder();
    xmlWrapAclRecord(aclBuff, acl);

    write(aclBuff.toString().getBytes(XML_DEFAULT_ENCODING));

    if (feedLogBuilder != null) {
      try {
        feedLogBuilder.append(aclBuff);
      } catch (IOException e) {
        // This won't happen with StringBuffer or StringBuilder.
        LOGGER.log(Level.WARNING, "Exception while constructing feed log:", e);
      }
    }
  }

  /*
   * Generate the ACL principal XML data.
   */
  private void getPrincipalXml(Document acl, StringBuilder buff)
      throws IOException, RepositoryException {
    Property property;

    property = acl.findProperty(SpiConstants.PROPNAME_ACLUSERS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.USER, AclAccess.PERMIT);
    }

    property = acl.findProperty(SpiConstants.PROPNAME_ACLGROUPS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.GROUP, AclAccess.PERMIT);
    }

    property = acl.findProperty(SpiConstants.PROPNAME_ACLDENYUSERS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.USER, AclAccess.DENY);
    }

    property = acl.findProperty(SpiConstants.PROPNAME_ACLDENYGROUPS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.GROUP, AclAccess.DENY);
    }
  }

  /*
   * Wrap the ACL principal info as XML data.
   */
  private static void wrapAclPrincipal(StringBuilder buff, Property property,
      AclScope scope, AclAccess access)
      throws RepositoryException, IOException {
    ValueImpl value;
    while ((value = (ValueImpl) property.nextValue()) != null) {
      Principal principal = (value instanceof PrincipalValue)
          ? ((PrincipalValue) value).getPrincipal()
          : new Principal(value.toString().trim());
      if (!Strings.isNullOrEmpty(principal.getName())) {
        buff.append("<").append(XML_PRINCIPAL);
        if (principal.getPrincipalType() ==
            SpiConstants.PrincipalType.UNQUALIFIED) {
          // UNQUALIFIED is a special-case on the GSA to allow us to prevent the
          // GSA from mistakeningly finding a domain in the principal name.
          XmlUtils.xmlAppendAttr(ServletUtil.XMLTAG_PRINCIPALTYPE_ATTRIBUTE,
              SpiConstants.PrincipalType.UNQUALIFIED.toString(), buff);
        }
        if (!Strings.isNullOrEmpty(principal.getNamespace())) {
          XmlUtils.xmlAppendAttr(ServletUtil.XMLTAG_NAMESPACE_ATTRIBUTE,
                                 principal.getNamespace(), buff);
        }
        // The GSA's default is EVERYTHING_CASE_SENSITIVE. No need to send the
        // attribute when it is the default.
        if (principal.getCaseSensitivityType()
            != SpiConstants.CaseSensitivityType.EVERYTHING_CASE_SENSITIVE) {
          XmlUtils.xmlAppendAttr(
              ServletUtil.XMLTAG_CASESENSITIVITYTYPE_ATTRIBUTE,
              principal.getCaseSensitivityType().toString(), buff);
        }
        XmlUtils.xmlAppendAttr(XML_SCOPE, scope.toString(), buff);
        XmlUtils.xmlAppendAttr(XML_ACCESS, access.toString(), buff);
        buff.append(">");
        XmlUtils.xmlAppendAttrValue(principal.getName(), buff);
        XmlUtils.xmlAppendEndTag(XML_PRINCIPAL, buff);
      }
    }
  }

  /**
   * Wrap the metadata and append it to the string buffer. Empty metadata
   * properties are not appended.
   *
   * @param buf string buffer
   * @param document Document
   * @throws RepositoryException if error reading Property from Document
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private void xmlWrapMetadata(StringBuilder buf, Document document)
      throws RepositoryException, IOException {
    boolean overwriteAcls = DocUtils.getOptionalBoolean(document,
        SpiConstants.PROPNAME_OVERWRITEACLS, true);
    buf.append('<').append(XML_METADATA);
    if (!overwriteAcls) {
      XmlUtils.xmlAppendAttr(XML_OVERWRITEACLS,
          Value.getBooleanValue(false).toString(), buf);
    }
    buf.append(">\n");

    // Add all the metadata supplied by the Connector.
    Set<String> propertyNames = document.getPropertyNames();
    if ((propertyNames == null) || propertyNames.isEmpty()) {
      LOGGER.log(Level.WARNING, "Property names set is empty");
    } else {
      // Sort property names so that metadata is written in a canonical form.
      // The GSA's metadata change detection logic depends on the metadata to be
      // in the same order each time in order to prevent reindexing.
      propertyNames = new TreeSet<String>(propertyNames);
      for (String name : propertyNames) {
        if (propertySkipSet.contains(name)) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            logOneProperty(document, name);
          }
          continue;
        }
        Property property = document.findProperty(name);
        if (property != null) {
          wrapOneProperty(buf, name, property);
        }
      }
    }
    XmlUtils.xmlAppendEndTag(XML_METADATA, buf);
  }

  /**
   * Wrap a single Property and append to string buffer. Does nothing if the
   * Property's value is null or zero-length.
   *
   * @param buf string builder
   * @param name the property's name
   * @param property Property
   * @throws RepositoryException if error reading Property from Document
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private static void wrapOneProperty(StringBuilder buf, String name,
      Property property) throws RepositoryException, IOException {
    ValueImpl value = null;
    while ((value = (ValueImpl) property.nextValue()) != null) {
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.finest("PROPERTY: " + name + " = \"" + value.toString() + "\"");
      }
      String valString = value.toFeedXml();
      if (valString != null && valString.length() > 0) {
        buf.append("<").append(XML_META);
        XmlUtils.xmlAppendAttr(XML_NAME, name, buf);
        XmlUtils.xmlAppendAttr(XML_CONTENT, valString, buf);
        buf.append("/>\n");
      }
    }
  }

  /**
   * Log the given Property's values.  This is called for
   * the small set of document properties that are not simply
   * added to the feed via wrapOneProperty().
   */
  private void logOneProperty(Document document, String name)
      throws RepositoryException, IOException {
    if (SpiConstants.PROPNAME_CONTENT.equals(name)) {
      LOGGER.finest("PROPERTY: " + name + " = \"...content...\"");
    } else {
      Property property = document.findProperty(name);
      if (property != null) {
        ValueImpl value = null;
        while ((value = (ValueImpl) property.nextValue()) != null) {
          LOGGER.finest("PROPERTY: " + name + " = \"" + value.toString()
                        + "\"");
        }
      }
    }
  }

  /**
   * Return an InputStream for the Document's content.
   */
  private InputStream getContentStream(Document document,
      ContentEncoding documentContentEncoding,
      ContentEncoding alternateEncoding) throws RepositoryException {
    InputStream contentStream;
      InputStream original = new BigEmptyDocumentFilterInputStream(
          DocUtils.getOptionalStream(document, SpiConstants.PROPNAME_CONTENT),
          fileSizeLimit.maxDocumentSize());
      InputStream encodedContentStream;
      if (documentContentEncoding == null) {
        encodedContentStream = getEncodedStream(contentEncoding, 
            original, (Context.getInstance().getTeedFeedFile() != null),
            1024 * 1024);
      } else {
        encodedContentStream = original;
      }

      InputStream encodedAlternateStream = getEncodedStream(alternateEncoding, 
          AlternateContentFilterInputStream.getAlternateContent(
          DocUtils.getOptionalString(document, SpiConstants.PROPNAME_TITLE), 
          DocUtils.getOptionalString(document, SpiConstants.PROPNAME_MIMETYPE)),
          false, 2048);

      return new AlternateContentFilterInputStream(
          encodedContentStream, encodedAlternateStream, this);
  }

  /**
   * Wrap the content stream with the suitable encoding (either
   * Base64 or Base64Compressed, based upon GSA encoding support.
   */
  // TODO: Don't compress tiny content or already compressed data
  // (based on mimetype).  This is harder than it sounds.
  private InputStream getEncodedStream(ContentEncoding contentEncoding,
      InputStream content, boolean wrapLines, int ioBufferSize) {
    if (contentEncoding == ContentEncoding.BASE64COMPRESSED) {
      return new Base64FilterInputStream(
          new CompressedFilterInputStream(content, ioBufferSize), wrapLines);
    } else {
      return new Base64FilterInputStream(content, wrapLines);
    }
  }
}

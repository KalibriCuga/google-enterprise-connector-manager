// Copyright (C) 2006 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.spi;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Simple implementation of the ConnectorType interface. Implementors may use
 * this directly or for reference. This implementation has no I18N - it just
 * uses a list of configuration keys for both validation and display.
 */
public class SimpleConnectorType implements ConnectorType {

  private static final String HIDDEN = "hidden";
  private static final String STARS = "*****";
  private static final String VALUE = "value";
  private static final String NAME = "name";
  private static final String TEXT = "text";
  private static final String TYPE = "type";
  private static final String INPUT = "input";
  private static final String CLOSE_ELEMENT = "/>";
  private static final String OPEN_ELEMENT = "<";
  private static final String PASSWORD = "password";
  private static final String TR_END = "</tr>\r\n";
  private static final String TD_END = "</td>\r\n";
  private static final String TD_START = "<td>";
  private static final String TR_START = "<tr>\r\n";


  private List keys = null;
  private Set keySet = null;
  private String initialConfigForm = null;

  public SimpleConnectorType() {
    //
  }

  /**
   * Set the keys that are required for configuration. One of the overloadings
   * of this method must be called exactly once before the SPI methods are used.
   * 
   * @param keys A list of String keys
   */
  public void setConfigKeys(List keys) {
    if (this.keys != null) {
      throw new IllegalStateException();
    }
    this.keys = keys;
    this.keySet = new HashSet(keys);
  }

  /**
   * Set the keys that are required for configuration. One of the overloadings
   * of this method must be called exactly once before the SPI methods are used.
   * 
   * @param keys An array of String keys
   */
  public void setConfigKeys(String[] keys) {
    setConfigKeys(Arrays.asList(keys));
  }

  /**
   * Sets the form to be used by this configurer. This is optional. If this
   * method is used, it must be called before the SPI methods are used.
   * 
   * @param formSnippet A String snippet of html - see the COnfigurer interface
   */
  public void setInitialConfigForm(String formSnippet) {
    if (this.initialConfigForm != null) {
      throw new IllegalStateException();
    }
    this.initialConfigForm = formSnippet;
  }

  private String getInitialConfigForm() {
    if (initialConfigForm != null) {
      return initialConfigForm;
    }
    if (keys == null) {
      throw new IllegalStateException();
    }
    this.initialConfigForm = makeConfigForm(null);
    return initialConfigForm;
  }

  private boolean validateConfigMap(Map configData) {
    for (Iterator i = keys.iterator(); i.hasNext();) {
      String key = (String) i.next();
      String val = (String) configData.get(key);
      if (val == null || val.length() == 0) {
        return false;
      }
    }
    return true;
  }

  private void appendAttribute(StringBuffer buf, String attrName,
      String attrValue) {
    buf.append(" ");
    buf.append(attrName);
    buf.append("=\"");
    // TODO xml-encode the special characters (< > " etc.)
    buf.append(attrValue);
    buf.append("\"");
  }

  /**
   * Make a config form snippet using the keys (in the supplied order) and, if
   * passed a non-null config map, pre-filling values in from that map
   * 
   * @param configMap
   * @return config form snippet
   */
  private String makeConfigForm(Map configMap) {
    StringBuffer buf = new StringBuffer(2048);
    for (Iterator i = keys.iterator(); i.hasNext();) {
      String key = (String) i.next();
      appendStartRow(buf, key);
      buf.append(OPEN_ELEMENT);
      buf.append(INPUT);
      if (key.equalsIgnoreCase(PASSWORD)) {
        appendAttribute(buf, TYPE, PASSWORD);
      } else {
        appendAttribute(buf, TYPE, TEXT);
      }
      appendAttribute(buf, NAME, key);
      if (configMap != null) {
        String value = (String) configMap.get(key);
        if (value != null) {
          appendAttribute(buf, VALUE, value);
        }
      }
      appendEndRow(buf);
    }
    return buf.toString();
  }

  private String makeValidatedForm(Map configMap) {
    StringBuffer buf = new StringBuffer(2048);
    for (Iterator i = keys.iterator(); i.hasNext();) {
      String key = (String) i.next();
      appendStartRow(buf, key);

      String value = (String) configMap.get(key);
      if (value == null) {
        buf.append(OPEN_ELEMENT);
        buf.append(INPUT);
        if (key.equalsIgnoreCase(PASSWORD)) {
          appendAttribute(buf, TYPE, PASSWORD);
        } else {
          appendAttribute(buf, TYPE, TEXT);
        }
      } else {
        if (key.equalsIgnoreCase(PASSWORD)) {
          buf.append(STARS);
        } else {
          buf.append(value);
        }
        buf.append(OPEN_ELEMENT);
        buf.append(INPUT);
        appendAttribute(buf, TYPE, HIDDEN);
        appendAttribute(buf, VALUE, value);
      }
      appendAttribute(buf, NAME, key);
      appendEndRow(buf);
    }
    
    // toss in all the stuff that's in the map but isn't in the keyset
    // taking care to list them in alphabetic order (this is mainly for
    // testability).
    Iterator i = new TreeSet(configMap.keySet()).iterator();
    while (i.hasNext()) {
      String key = (String) i.next();
      if (!keySet.contains(key)) {
        // add another hidden field to preserve this data
        String val = (String) configMap.get(key);
        buf.append("<input type=\"hidden\" value=\"");
        buf.append(val);
        buf.append("\" name=\"");
        buf.append(key);
        buf.append("\"/>\r\n");
      }
    }
    return buf.toString();
  }

  private void appendStartRow(StringBuffer buf, String key) {
    buf.append(TR_START);
    buf.append(TD_START);
    buf.append(key);
    buf.append(TD_END);
    buf.append(TD_START);
  }

  private void appendEndRow(StringBuffer buf) {
    buf.append(CLOSE_ELEMENT);
    buf.append(TD_END);
    buf.append(TR_END);
  }

  /**
   * Returns an embedded configurer, which may depend on the configData already
   * supplied. This method is here primarily so that implementors can override
   * it.
   * 
   * @param configData
   * @param language
   * @return another Configurer, which may be null
   */
  ConnectorType getEmbeddedConfigurer(Map configData, String language) {
    return null;
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.google.enterprise.connector.spi.Configurer#getConfigForm(java.lang.String)
   */
  public ConfigureResponse getConfigForm(String language) {
    ConfigureResponse result =
        new ConfigureResponse("", getInitialConfigForm());
    return result;
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.google.enterprise.connector.spi.Configurer#validateConfig(java.util.Map,
   *      java.lang.String)
   */
  public ConfigureResponse validateConfig(Map configData, String language) {
    if (validateConfigMap(configData)) {
      // all is ok
      return null;
    }
    String form = makeValidatedForm(configData);
    return new ConfigureResponse("Some required configuration is missing", form);
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.google.enterprise.connector.spi.Configurer
   *      #getPopulatedConfigForm(java.util.Map,java.lang.String)
   */
  public ConfigureResponse getPopulatedConfigForm(Map configMap, String language) {
    ConfigureResponse result =
        new ConfigureResponse("", makeConfigForm(configMap));
    return result;
  }

}

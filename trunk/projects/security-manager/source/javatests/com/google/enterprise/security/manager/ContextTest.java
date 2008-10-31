// Copyright (C) 2008 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.security.manager;

import com.google.enterprise.connector.instantiator.InstantiatorException;
import com.google.enterprise.connector.manager.Manager;
import com.google.enterprise.connector.persist.ConnectorExistsException;
import com.google.enterprise.connector.persist.ConnectorNotFoundException;
import com.google.enterprise.connector.persist.PersistentStoreException;
import com.google.enterprise.saml.server.BackEnd;
import com.google.enterprise.saml.server.MockBackEnd;
import com.google.enterprise.sessionmanager.SessionManagerInterface;

import junit.framework.TestCase;

/**
 * Test class for security.manager.Context. This is based on the corresponding
 * test for connector.manager.Context. Eventually, we plan that these classes
 * will merge, and so will the tests.
 */
public class ContextTest extends TestCase {

  private static final String CONTEXT_LOCATION = "testdata/contextTest/applicationContext.xml";

  /**
   * We do Context.refresh() before and after so as not to interfere with other
   * tests that might use a Context
   */
  public final void testBasicFunctionality() {
    Context.refresh();
    Context context = Context.getInstance();
    context.setStandaloneContext(CONTEXT_LOCATION);
    BackEnd backEnd = context.getBackEnd();
    SessionManagerInterface sessionManager = backEnd.getSessionManager();
    assertTrue(sessionManager instanceof LocalSessionManager);
    assertTrue(backEnd instanceof MockBackEnd);
    MockBackEnd mockBackEnd = (MockBackEnd) backEnd;
    Manager connectorManager = mockBackEnd.getConnectorManager();
    try {
      connectorManager.setConnectorConfig("joetheplumber", "", null, null, true);
    } catch (ConnectorNotFoundException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (ConnectorExistsException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (PersistentStoreException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (InstantiatorException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    Context.refresh();
  }
}

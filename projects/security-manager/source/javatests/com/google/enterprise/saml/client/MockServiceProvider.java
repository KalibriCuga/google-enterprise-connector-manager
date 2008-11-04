// Copyright 2008 Google Inc.  All Rights Reserved.
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

package com.google.enterprise.saml.client;

import com.google.enterprise.saml.common.HttpServletRequestClientAdapter;
import com.google.enterprise.saml.common.HttpServletResponseClientAdapter;
import com.google.enterprise.saml.server.MockIdentityProvider;

import org.opensaml.common.SAMLObject;
import org.opensaml.common.binding.SAMLMessageContext;
import org.opensaml.common.xml.SAMLConstants;
import org.opensaml.saml2.binding.decoding.HTTPSOAP11Decoder;
import org.opensaml.saml2.binding.encoding.HTTPRedirectDeflateEncoder;
import org.opensaml.saml2.binding.encoding.HTTPSOAP11Encoder;
import org.opensaml.saml2.core.ArtifactResolve;
import org.opensaml.saml2.core.ArtifactResponse;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.AuthnRequest;
import org.opensaml.saml2.core.AuthnStatement;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.Response;
import org.opensaml.saml2.core.StatusCode;
import org.opensaml.saml2.metadata.ArtifactResolutionService;
import org.opensaml.ws.transport.http.HttpServletResponseAdapter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import static com.google.enterprise.saml.common.GsaConstants.GSA_ARTIFACT_PARAM_NAME;
import static com.google.enterprise.saml.common.GsaConstants.GSA_RELAY_STATE_PARAM_NAME;
import static com.google.enterprise.saml.common.OpenSamlUtil.GOOGLE_ISSUER;
import static com.google.enterprise.saml.common.OpenSamlUtil.GOOGLE_PROVIDER_NAME;
import static com.google.enterprise.saml.common.OpenSamlUtil.makeArtifactResolve;
import static com.google.enterprise.saml.common.OpenSamlUtil.makeAuthnRequest;
import static com.google.enterprise.saml.common.OpenSamlUtil.makeIssuer;
import static com.google.enterprise.saml.common.OpenSamlUtil.makeSamlMessageContext;
import static com.google.enterprise.saml.common.OpenSamlUtil.runDecoder;
import static com.google.enterprise.saml.common.OpenSamlUtil.runEncoder;
import static com.google.enterprise.saml.common.SamlTestUtil.errorServletResponse;
import static com.google.enterprise.saml.common.SamlTestUtil.htmlServletResponse;
import static com.google.enterprise.saml.common.SamlTestUtil.initializeServletResponse;

/**
 * The MockServiceProvider class implements a servlet pretending to be the part of a SAML Service
 * Provider that receives a service request from the user agent and initiates an authn request from
 * an identity provider.
 */
public class MockServiceProvider extends HttpServlet {
  private static final String className = MockServiceProvider.class.getName();
  private static final Logger logger = Logger.getLogger(className);
  private static final long serialVersionUID = 1L;

  private final String serviceUrl;
  private final String acsUrl;
  private final MockIdentityProvider idp;
  private final MockArtifactResolver resolver;
  private final int acsIndex;
  private final ArtifactResolutionService resolutionService;

  /**
   * Creates a new mock SAML service provider with the given identity provider.
   *
   * @param serviceUrl The URL for the provided service.
   * @param acsUrl The URL for the assertion-consumer endpoint.
   * @param idp The identity provider object.
   * @param resolver The artifact resolver object.
   * @throws ServletException
   */
  public MockServiceProvider(String serviceUrl, String acsUrl, MockIdentityProvider idp, MockArtifactResolver resolver) throws ServletException {
    init(new MockServletConfig());
    this.serviceUrl = serviceUrl;
    this.acsUrl = acsUrl;
    this.idp = idp;
    this.resolver = resolver;
    acsIndex = idp.addAssertionConsumerService(SAMLConstants.SAML2_ARTIFACT_BINDING_URI, acsUrl);
    resolutionService = null;
    //resolutionService = resolver.getArtifactResolutionService();
  }

  public String getServiceUrl() {
    return serviceUrl;
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    String url = req.getRequestURL().toString();
    if (url.startsWith(serviceUrl)) {
      provideService(req, resp, url.substring(serviceUrl.length()));
    } else if (url.equals(acsUrl)) {
      consumeArtifact(req, resp);
    } else {
      errorServletResponse(resp, HttpServletResponse.SC_NOT_FOUND);
    }
  }

  private void provideService(HttpServletRequest req, HttpServletResponse resp, String relayState)
      throws ServletException, IOException {
    Object isAuthenticated = req.getSession().getAttribute("isAuthenticated");
    logger.log(Level.FINE, "isAuthenticate = " + isAuthenticated);
    if (isAuthenticated == Boolean.TRUE) {
      ifAllowed(resp);
    } else if (isAuthenticated == Boolean.FALSE) {
      errorServletResponse(resp, HttpServletResponse.SC_UNAUTHORIZED);
    } else {
      ifUnknown(resp, relayState);
    }
  }

  private void ifAllowed(HttpServletResponse resp) throws IOException {
    logger.entering(className, "ifAllowed");
    PrintWriter out = htmlServletResponse(resp);
    out.print("<html><head><title>What you need</title></head>");
    out.print("<body><h1>What you need...</h1><p>...is what we've got!</p></body></html>");
    out.close();
    logger.exiting(className, "ifAllowed");
  }

  private void ifUnknown(HttpServletResponse resp, String relayState) throws ServletException {
    logger.entering(className, "ifUnknown");
    SAMLMessageContext<SAMLObject, AuthnRequest, NameID> context = makeSamlMessageContext();
    {
      AuthnRequest authnRequest = makeAuthnRequest();
      authnRequest.setProviderName(GOOGLE_PROVIDER_NAME);
      authnRequest.setIssuer(makeIssuer(GOOGLE_ISSUER));
      authnRequest.setIsPassive(false);
      authnRequest.setAssertionConsumerServiceIndex(acsIndex);
      context.setOutboundSAMLMessage(authnRequest);
    }
    context.setRelayState(relayState);
    context.setPeerEntityEndpoint(idp.getSingleSignOnService());
    context.setOutboundMessageTransport(new HttpServletResponseAdapter(resp, true));
    initializeServletResponse(resp);
    runEncoder(new HTTPRedirectDeflateEncoder(), context);
    logger.exiting(className, "ifUnknown");
  }

  private void consumeArtifact(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    HttpSession session = req.getSession();
    String artifact = req.getParameter(GSA_ARTIFACT_PARAM_NAME);
    String relayState = req.getParameter(GSA_RELAY_STATE_PARAM_NAME);
    if (artifact == null) {
      throw new ServletException("No artifact in request.");
    }
    ArtifactResponse artifactResponse =
        receiveArtifactResponse(
            resolver.resolve(sendArtifactResolve(makeArtifactResolve(artifact),
                                                 relayState)));
    HttpServletResponseAdapter result = new HttpServletResponseAdapter(resp, true);
    SAMLObject message = artifactResponse.getMessage();
    if (! (message instanceof Response)) {
      errorServletResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } else {
      Response response = (Response) message;
      String code = response.getStatus().getStatusCode().getValue();
      if (code.equals(StatusCode.SUCCESS_URI)) {
        Assertion assertion = response.getAssertions().get(0);
        session.setAttribute("isAuthenticated", true);
        session.setAttribute("verifiedIdentity", assertion.getSubject().getNameID().getValue());
        session.setAttribute("verificationStatement", assertion.getStatements(AuthnStatement.DEFAULT_ELEMENT_NAME).get(0));
      } else if (code.equals(StatusCode.REQUEST_DENIED_URI)) {
        session.setAttribute("isAuthenticated", false);
      } else if (code.equals(StatusCode.AUTHN_FAILED_URI)) {
        // Do nothing.  The service provider will restart the authentication.
      } else {
        throw new ServletException("Bad <Response>:" + code);
      }
    }
    result.sendRedirect(relayState);
  }

  private MockHttpServletRequest sendArtifactResolve(ArtifactResolve request, String relayState)
      throws ServletException {
    SAMLMessageContext<SAMLObject, ArtifactResolve, NameID> context = makeSamlMessageContext();
    HttpServletRequestClientAdapter transport = new HttpServletRequestClientAdapter();
    context.setPeerEntityEndpoint(resolutionService);
    context.setOutboundMessageTransport(transport);
    context.setOutboundSAMLMessage(request);
    if (relayState != null) {
      context.setRelayState(relayState);
    }
    runEncoder(new HTTPSOAP11Encoder(), context);
    return transport.getRequest();
  }

  private ArtifactResponse receiveArtifactResponse(MockHttpServletResponse response)
      throws ServletException {
    SAMLMessageContext<ArtifactResponse, SAMLObject, NameID> context = makeSamlMessageContext();
    HttpServletResponseClientAdapter transport = new HttpServletResponseClientAdapter(response);
    context.setInboundMessageTransport(transport);
    context.setPeerEntityEndpoint(resolutionService);
    runDecoder(new HTTPSOAP11Decoder(), context);
    return context.getInboundSAMLMessage();
  }
}

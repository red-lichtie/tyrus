/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.tyrus.servlet;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Set;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.WebConnection;

import org.glassfish.tyrus.server.ServerContainerFactory;
import org.glassfish.tyrus.server.TyrusServerContainer;
import org.glassfish.tyrus.websockets.Connection;
import org.glassfish.tyrus.websockets.HandshakeException;
import org.glassfish.tyrus.websockets.WebSocketEngine;
import org.glassfish.tyrus.websockets.WebSocketRequest;

/**
 * Filter used for Servlet integration.
 * <p/>
 * Consumes only requests with {@link WebSocketEngine#SEC_WS_KEY_HEADER} headers present, all others are
 * passed back to {@link FilterChain}.
 *
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 */
public class TyrusServletFilter implements Filter {

    private static final int INFORMATIONAL_FIXED_PORT = 8080;
    private final static Logger LOGGER = Logger.getLogger(TyrusServletFilter.class.getName());
    private final WebSocketEngine engine;
    private TyrusServerContainer serverContainer = null;

    // @WebSocketEndpoint annotated classes and classes extending ServerApplicationConfiguration
    private final Set<Class<?>> classes;

    /**
     * Constructor.
     *
     * @param classes set of classes to be used as configuration or endpoints.
     */
    public TyrusServletFilter(Set<Class<?>> classes) {
        this.classes = classes;
        engine = WebSocketEngine.getEngine();
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        String contextRoot = filterConfig.getServletContext().getContextPath();
        this.serverContainer = ServerContainerFactory.create(ServletContainer.class, contextRoot,INFORMATIONAL_FIXED_PORT, classes);
        try {
            serverContainer.start();
        } catch (Exception e) {
            throw new ServletException("Web socket server initialization failed.", e);
        }
    }

    private class TyrusHttpUpgradeHandlerProxy extends TyrusHttpUpgradeHandler {

        private TyrusHttpUpgradeHandler handler;

        @Override
        public void init(WebConnection wc) {
            handler.init(wc);
        }

        @Override
        public void onDataAvailable() {
            handler.onDataAvailable();
        }

        @Override
        public void onAllDataRead() {
            handler.onAllDataRead();
        }

        @Override
        public void onError(Throwable t) {
            handler.onError(t);
        }

        @Override
        public void destroy() {
            handler.destroy();
        }

        @Override
        public void setWebSocketHolder(WebSocketEngine.WebSocketHolder webSocketHolder) {
            handler.setWebSocketHolder(webSocketHolder);
        }

        @Override
        ServletOutputStream getOutputStream() {
            return handler.getOutputStream();
        }

        void setHandler(TyrusHttpUpgradeHandler handler) {
            this.handler = handler;
        }
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        final HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;

        // check for mandatory websocket header
        final String header = httpServletRequest.getHeader(WebSocketEngine.SEC_WS_KEY_HEADER);
        if (header != null) {
            LOGGER.config("Setting up WebSocket protocol handler");

            final TyrusHttpUpgradeHandlerProxy handler = new TyrusHttpUpgradeHandlerProxy();

            final ConnectionImpl webSocketConnection = new ConnectionImpl(handler, httpServletResponse);
            WebSocketRequest webSocketRequest = new WebSocketRequest() {
                @Override
                public String getRequestURI() {
                    return httpServletRequest.getRequestURI();
                }

                @Override
                public String getQueryString() {
                    return httpServletRequest.getQueryString();
                }

                @Override
                public Connection getConnection() {
                    return webSocketConnection;
                }
            };

            Enumeration<String> headerNames = httpServletRequest.getHeaderNames();

            while (headerNames.hasMoreElements()) {
                String key = headerNames.nextElement();

                webSocketRequest.getHeaders().put(key, httpServletRequest.getHeader(key));
            }

            webSocketRequest.setRequestPath(httpServletRequest.getServletPath());

            try {
                if (!engine.upgrade(webSocketConnection, webSocketRequest, new WebSocketEngine.WebSocketHolderListener() {
                    @Override
                    public void onWebSocketHolder(WebSocketEngine.WebSocketHolder webSocketHolder) throws IOException {
                        LOGGER.config("Upgrading Servlet request");
                        handler.setHandler(httpServletRequest.upgrade(TyrusHttpUpgradeHandler.class));
                        handler.setWebSocketHolder(engine.getWebSocketHolder(webSocketConnection));
                    }
                })) {
                    filterChain.doFilter(request, response);
                    return;
                }

            } catch (HandshakeException e) {
                // TODO
//                ctx.write(composeHandshakeError(request, e));
            }

            // Servlet bug ?? Not sure why we need to flush the headers
            response.flushBuffer();
            LOGGER.config("Handshake Complete");
        } else {
            filterChain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
        serverContainer.stop();
    }
}
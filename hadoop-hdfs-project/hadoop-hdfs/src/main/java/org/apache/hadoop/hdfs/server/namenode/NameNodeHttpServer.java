/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.server.common.JspHelper;
import org.apache.hadoop.hdfs.server.namenode.web.resources.NamenodeWebHdfsMethods;
import org.apache.hadoop.hdfs.web.AuthFilter;
import org.apache.hadoop.hdfs.web.WebHdfsFileSystem;
import org.apache.hadoop.hdfs.web.resources.Param;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;

import javax.servlet.ServletContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_ADMIN;
import org.apache.hadoop.hdfs.server.namenode.startupprogress.StartupProgress;
import org.apache.hadoop.hdfs.web.resources.UserParam;
import org.apache.hadoop.http.HttpServer3;

/**
 * Encapsulates the HTTP server started by the NameNode.
 */
@InterfaceAudience.Private
public class NameNodeHttpServer {
  private HttpServer3 httpServer;
  private final Configuration conf;
  private final NameNode nn;
  
  private InetSocketAddress httpAddress;
  private InetSocketAddress httpsAddress;
  private InetSocketAddress bindAddress;
  
  public static final String NAMENODE_ADDRESS_ATTRIBUTE_KEY =
      "name.node.address";
  public static final String FSIMAGE_ATTRIBUTE_KEY = "name.system.image";
  protected static final String NAMENODE_ATTRIBUTE_KEY = "name.node";
  public static final String STARTUP_PROGRESS_ATTRIBUTE_KEY = "startup.progress";
  
  public NameNodeHttpServer(Configuration conf, NameNode nn,
      InetSocketAddress bindAddress) {
    this.conf = conf;
    this.nn = nn;
    this.bindAddress = bindAddress;
  }
  
  void start() throws IOException {
    final String infoHost = bindAddress.getHostName();
    int infoPort = bindAddress.getPort();
    HttpServer3.Builder builder = new HttpServer3.Builder().setName("hdfs")
        .addEndpoint(URI.create(infoHost).create(("http://" + NetUtils.getHostPortString(bindAddress))))
        .setFindPort(infoPort == 0).setConf(conf).setACL(
        new AccessControlList(conf.get(DFS_ADMIN, " ")))
        .setSecurityEnabled(UserGroupInformation.isSecurityEnabled())
        .setUsernameConfKey(
            DFSConfigKeys.DFS_NAMENODE_INTERNAL_SPNEGO_USER_NAME_KEY)
        .setKeytabConfKey(DFSUtil.getSpnegoKeytabKey(conf,
            DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY));

    boolean certSSL = conf.getBoolean(DFSConfigKeys.DFS_HTTPS_ENABLE_KEY, false);
    if (certSSL) {
      httpsAddress = NetUtils.createSocketAddr(conf.get(
          DFSConfigKeys.DFS_NAMENODE_HTTPS_ADDRESS_KEY,
          DFSConfigKeys.DFS_NAMENODE_HTTPS_ADDRESS_DEFAULT));

      builder.addEndpoint(URI.create("https://"
          + NetUtils.getHostPortString(httpsAddress)));
      Configuration sslConf = new Configuration(false);
      sslConf.setBoolean(DFSConfigKeys.DFS_CLIENT_HTTPS_NEED_AUTH_KEY, conf
          .getBoolean(DFSConfigKeys.DFS_CLIENT_HTTPS_NEED_AUTH_KEY,
              DFSConfigKeys.DFS_CLIENT_HTTPS_NEED_AUTH_DEFAULT));
      sslConf.addResource(conf.get(
          DFSConfigKeys.DFS_SERVER_HTTPS_KEYSTORE_RESOURCE_KEY,
          DFSConfigKeys.DFS_SERVER_HTTPS_KEYSTORE_RESOURCE_DEFAULT));
      DFSUtil.loadSslConfToHttpServerBuilder(builder, sslConf);
    }

    httpServer = builder.build();
    if (WebHdfsFileSystem.isEnabled(conf, HttpServer3.LOG)) {
      //add SPNEGO authentication filter for webhdfs
      final String name = "SPNEGO";
      final String classname = AuthFilter.class.getName();
      final String pathSpec = WebHdfsFileSystem.PATH_PREFIX + "/*";
      Map<String, String> params = getAuthFilterParams(conf);
      HttpServer3.defineFilter(httpServer.getWebAppContext(), name, classname, params,
          new String[]{pathSpec});
      HttpServer3.LOG.info("Added filter '" + name + "' (class=" + classname + ")");

      // add webhdfs packages
      httpServer.addJerseyResourcePackage(
          NamenodeWebHdfsMethods.class.getPackage().getName()
          + ";" + Param.class.getPackage().getName(), pathSpec);
    }

    httpServer.setAttribute(NAMENODE_ATTRIBUTE_KEY, nn);
    httpServer.setAttribute(JspHelper.CURRENT_CONF, conf);
    setupServlets(httpServer, conf);
    httpServer.start();
    httpAddress = httpServer.getConnectorAddress(0);
    if (certSSL) {
      httpsAddress = httpServer.getConnectorAddress(1);
      // assume same ssl port for all datanodes
      InetSocketAddress datanodeSslPort = NetUtils.createSocketAddr(conf.get(
          DFSConfigKeys.DFS_DATANODE_HTTPS_ADDRESS_KEY, infoHost + ":" + 50475));
      httpServer.setAttribute(DFSConfigKeys.DFS_DATANODE_HTTPS_PORT_KEY, datanodeSslPort
          .getPort());
    }
  }

  private Map<String, String> getAuthFilterParams(Configuration conf)
      throws IOException {
    Map<String, String> params = new HashMap<String, String>();
    String principalInConf = conf
        .get(DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL_KEY);
    if (principalInConf != null && !principalInConf.isEmpty()) {
      params
          .put(
              DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL_KEY,
              SecurityUtil.getServerPrincipal(principalInConf,
                  bindAddress.getHostName()));
    } else if (UserGroupInformation.isSecurityEnabled()) {
      HttpServer3.LOG.error(
          "WebHDFS and security are enabled, but configuration property '"
          + DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL_KEY + "' is not set.");
    }
    String httpKeytab = conf.get(DFSUtil.getSpnegoKeytabKey(conf,
        DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY));
    if (httpKeytab != null && !httpKeytab.isEmpty()) {
      params.put(
          DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_KEYTAB_KEY,
          httpKeytab);
    } else if (UserGroupInformation.isSecurityEnabled()) {
      HttpServer3.LOG.error(
          "WebHDFS and security are enabled, but configuration property '"
          + DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_KEYTAB_KEY + "' is not set.");
    }
    return params;
  }

  public void stop() throws Exception {
    if (httpServer != null) {
      httpServer.stop();
    }
  }

  public InetSocketAddress getHttpAddress() {
    return httpAddress;
  }

  public InetSocketAddress getHttpsAddress() {
    return httpsAddress;
  }
  
  /**
   * Sets address of namenode for use by servlets.
   * 
   * @param nameNodeAddress InetSocketAddress to set
   */
  public void setNameNodeAddress(InetSocketAddress nameNodeAddress) {
    httpServer.setAttribute(NAMENODE_ADDRESS_ATTRIBUTE_KEY,
        NetUtils.getConnectAddress(nameNodeAddress));
  }

  /**
   * Sets startup progress of namenode for use by servlets.
   * 
   * @param prog StartupProgress to set
   */
  public void setStartupProgress(StartupProgress prog) {
    httpServer.setAttribute(STARTUP_PROGRESS_ATTRIBUTE_KEY, prog);
  }

  private static void setupServlets(HttpServer3 httpServer, Configuration conf) {
    httpServer.addInternalServlet("startupProgress",
        StartupProgressServlet.PATH_SPEC, StartupProgressServlet.class);
    httpServer.addInternalServlet("getDelegationToken",
        GetDelegationTokenServlet.PATH_SPEC, GetDelegationTokenServlet.class,
        true);
    httpServer.addInternalServlet("renewDelegationToken",
        RenewDelegationTokenServlet.PATH_SPEC,
        RenewDelegationTokenServlet.class, true);
    httpServer.addInternalServlet("cancelDelegationToken",
        CancelDelegationTokenServlet.PATH_SPEC,
        CancelDelegationTokenServlet.class, true);
    httpServer.addInternalServlet("fsck", "/fsck", FsckServlet.class, true);
    httpServer
        .addInternalServlet("listPaths", "/listPaths/*", ListPathsServlet.class,
            false);
    httpServer
        .addInternalServlet("data", "/data/*", FileDataServlet.class, false);
    httpServer.addInternalServlet("checksum", "/fileChecksum/*",
        FileChecksumServlets.RedirectServlet.class, false);
    httpServer.addInternalServlet("contentSummary", "/contentSummary/*",
        ContentSummaryServlet.class, false);
  }

  public static NameNode getNameNodeFromContext(ServletContext context) {
    return (NameNode) context.getAttribute(NAMENODE_ATTRIBUTE_KEY);
  }

  public static Configuration getConfFromContext(ServletContext context) {
    return (Configuration) context.getAttribute(JspHelper.CURRENT_CONF);
  }

  public static InetSocketAddress getNameNodeAddressFromContext(
      ServletContext context) {
    return (InetSocketAddress) context
        .getAttribute(NAMENODE_ADDRESS_ATTRIBUTE_KEY);
  }
  
  /**
   * Returns StartupProgress associated with ServletContext.
   * 
   * @param context ServletContext to get
   * @return StartupProgress associated with context
   */
  public static StartupProgress getStartupProgressFromContext(
      ServletContext context) {
    return (StartupProgress)context.getAttribute(STARTUP_PROGRESS_ATTRIBUTE_KEY);
  }
}

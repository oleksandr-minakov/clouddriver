/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.docker.registry.api.v2.auth

import com.netflix.spinnaker.clouddriver.docker.registry.DockerRegistryConfiguration
import com.netflix.spinnaker.clouddriver.docker.registry.api.v2.exception.DockerRegistryAuthenticationException
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import retrofit.RestAdapter
import retrofit.RetrofitError
import retrofit.client.Header
import retrofit.http.GET
import retrofit.http.Headers
import retrofit.http.Path
import retrofit.http.Query

@Slf4j
class DockerBearerTokenService {
  private Map<String, TokenService> realmToService
  private Map<String, DockerBearerToken> cachedTokens
  public String basicAuth
  public String basicAuthHeader

  @Autowired
  String dockerApplicationName

  DockerBearerTokenService(String username, String password) {
    realmToService = new HashMap<String, TokenService>()
    cachedTokens = new HashMap<String, DockerBearerToken>()
    if (username || password) {
      basicAuth = new String(Base64.encoder.encode(("${username}:${password}").bytes))
      basicAuthHeader = "Basic $basicAuth"
    } else {
      basicAuth = null
      basicAuthHeader = null
    }
  }

  /*
   * Parsed according to http://www.ietf.org/rfc/rfc2617.txt
   */
  public AuthenticateDetails parseBearerAuthenticateHeader(String header) {
    String bearerPrefix = "bearer "
    String realmKey = "realm"
    String serviceKey = "service"
    String scopeKey = "scope"
    AuthenticateDetails result = new AuthenticateDetails()

    if (!bearerPrefix.equalsIgnoreCase(header.substring(0, bearerPrefix.length()))) {
      throw new DockerRegistryAuthenticationException("Docker registry must support 'Bearer' authentication.")
    } else {
      header = header.substring(bearerPrefix.length())
    }

    // Each parameter has the form <token>=(<token>|<quoted-string>).
    while (header.length() > 0) {
      String key
      String value

      def keyEnd = header.indexOf("=")
      if (keyEnd == -1) {
        throw new DockerRegistryAuthenticationException("Www-Authenticate header terminated with junk: '$header'.")
      }

      key = header.substring(0, keyEnd)
      header = header.substring(keyEnd + 1)
      if (header.length() == 0) {
        throw new DockerRegistryAuthenticationException("Www-Authenticate header unmatched parameter key: '$key'.")
      }

      // Parse a quoted string.
      if (header[0] == '"') {
        header = header.substring(1)

        def valueEnd = header.indexOf('"')
        if (valueEnd == -1) {
          throw new DockerRegistryAuthenticationException('Www-Authenticate header has unterminated " (quotation mark).')
        }

        value = header.substring(0, valueEnd)
        header = header.substring(valueEnd + 1)

        if (header.length() != 0) {
          if (header[0] != ",") {
            throw new DockerRegistryAuthenticationException("Www-Authenticate header params must be separated by , (comma).")
          }
          header = header.substring(1)
        }
      } else { // Parse an unquoted token.
        def valueEnd = header.indexOf(",")

        // In the case of the last parameter, there will be no terminating ',' character.
        if (valueEnd == -1) {
          value = header
          header = ""
        } else {
          value = header.substring(0, valueEnd)
          header = header.substring(valueEnd + 1)
        }
      }

      if (key.equalsIgnoreCase(realmKey)) {
        def url = new URL(value)
        result.realm = url.protocol + "://" + url.authority
        result.path = url.path
        if (result.path.length() > 0 && result.path[0] == "/") {
          result.path = result.path.substring(1)
        }
      } else if (key.equalsIgnoreCase(serviceKey)) {
        result.service = value
      } else if (key.equalsIgnoreCase(scopeKey)) {
        result.scope = value
      }
    }

    if (!result.realm) {
      throw new DockerRegistryAuthenticationException("Www-Authenticate header must provide 'realm' parameter.")
    }
    if (!result.service) {
      throw new DockerRegistryAuthenticationException("Www-Authenticate header must provide 'service' parameter.")
    }

    return result
  }

  private getTokenService(String realm) {
    log.warn("#DockerBearerTokenService #getTokenService REALM -> ${realm}")
    def tokenService = realmToService.get(realm)

    if (tokenService == null) {
      def builder = new RestAdapter.Builder().setEndpoint(realm).setLogLevel(RestAdapter.LogLevel.NONE).build()
      log.warn("#DockerBearerTokenService #getTokenService #beforeBuild")
      tokenService = builder.create(TokenService.class)
      realmToService[realm] = tokenService
    }

    return tokenService
  }

  public DockerBearerToken getToken(String repository) {
    return cachedTokens[repository]
  }

  public DockerBearerToken getToken(String repository, List<Header> headers) {
    log.warn("#DockerBearerToken #getToken")
    log.warn("#DockerBearerToken #getToken REPOSITORY -> $repository")
    log.warn("#DockerBearerToken #getToken HEADERS -> ${headers}")
    String authenticate = null

    headers.forEach { header ->
      if (header.name.equalsIgnoreCase("www-authenticate")) {
        log.warn("#DockerBearerToken #getToken HEADER -> ${header.toString()} = ${header.value}")
        authenticate = header.value
      }
    }

    if (!authenticate) {
      return null
    }

    def authenticateDetails
    try {
      log.warn("#DockerBearerToken #getToken #parseBearerAuthenticateHeader")
      authenticateDetails = parseBearerAuthenticateHeader(authenticate)
    } catch (Exception e) {
      throw new DockerRegistryAuthenticationException("Failed to parse www-authenticate header: ${e.message}")
    }

    log.warn("#DockerBearerToken #getToken #getTokenService")
    def tokenService = getTokenService(authenticateDetails.realm)
    def token
    if (basicAuth) {
      log.warn("#DockerBearerTokenService #basicAuth #before DETAILS -> ${authenticateDetails.path}")
      log.warn("#DockerBearerTokenService #basicAuth #before DETAILS -> ${authenticateDetails.realm}")
      log.warn("#DockerBearerTokenService #basicAuth #before DETAILS -> ${authenticateDetails.scope}")
      log.warn("#DockerBearerTokenService #basicAuth #before DETAILS -> ${authenticateDetails.service}")
      log.warn("#DockerBearerTokenService #basicAuth #before basicAuth -> ${basicAuth}")
      log.warn("#DockerBearerTokenService #basicAuth #before basicAuthHeader -> ${basicAuthHeader}")
      log.warn("#DockerBearerTokenService #basicAuth #before dockerApplicationName -> ${dockerApplicationName}")

      try {
        token = tokenService.getToken(authenticateDetails.path, authenticateDetails.service, authenticateDetails.scope, basicAuthHeader, dockerApplicationName)
      } catch (RetrofitError err) {
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.url}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.message}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.response.status}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.response.url}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.response.headers}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.response.reason}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.response.body.toString()}")
        log.error("#DockerBearerTokenService #basicAuth #RetrofitError -> ${err.response.body.in().text}")
        throw err
      }

      log.warn("#DockerBearerTokenService #basicAuth #after TOKEN -> ${token}")
    }
    else {
      log.warn("#DockerBearerTokenService #tokenAuth #before DETAILS -> ${authenticateDetails.path}")
      log.warn("#DockerBearerTokenService #tokenAuth #before DETAILS -> ${authenticateDetails.realm}")
      log.warn("#DockerBearerTokenService #tokenAuth #before DETAILS -> ${authenticateDetails.scope}")
      log.warn("#DockerBearerTokenService #tokenAuth #before DETAILS -> ${authenticateDetails.service}")
      log.warn("#DockerBearerTokenService #tokenAuth #before dockerApplicationName -> ${dockerApplicationName}")

      try {
        token = tokenService.getToken(authenticateDetails.path, authenticateDetails.service, authenticateDetails.scope, dockerApplicationName)

      } catch (RetrofitError err) {
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.url}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.message}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.response.status}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.response.url}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.response.headers}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.response.reason}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.response.body.toString()}")
        log.error("#DockerBearerTokenService #tokenAuth #RetrofitError -> ${err.response.body.in().text}")
        throw err
      }

      log.warn("#DockerBearerTokenService #tokenAuth #after TOKEN -> ${token}")
    }

    cachedTokens[repository] = token
    return token
  }

  private interface TokenService {
    @GET("/{path}")
    @Headers([
      "Docker-Distribution-API-Version: registry/2.0"
    ])
    DockerBearerToken getToken(@Path(value="path", encode=false) String path,
                               @Query(value="service") String service, @Query(value="scope") String scope,
                               @retrofit.http.Header("User-Agent") String agent)

    @GET("/{path}")
    @Headers([
      "Docker-Distribution-API-Version: registry/2.0"
    ])
    DockerBearerToken getToken(@Path(value="path", encode=false) String path, @Query(value="service") String service,
                               @Query(value="scope") String scope, @retrofit.http.Header("Authorization") String basic,
                               @retrofit.http.Header("User-Agent") String agent)
  }

  private class AuthenticateDetails {
    String realm
    String path
    String service
    String scope
  }
}

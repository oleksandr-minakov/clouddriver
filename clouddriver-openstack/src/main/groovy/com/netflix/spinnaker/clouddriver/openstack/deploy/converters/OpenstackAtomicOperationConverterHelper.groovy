/*
 * Copyright 2016 Target, Inc.
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

package com.netflix.spinnaker.clouddriver.openstack.deploy.converters

import com.fasterxml.jackson.databind.DeserializationFeature
import com.netflix.spinnaker.clouddriver.openstack.deploy.description.OpenstackAtomicOperationDescription
import com.netflix.spinnaker.clouddriver.openstack.security.OpenstackCredentials
import com.netflix.spinnaker.clouddriver.security.AbstractAtomicOperationsCredentialsSupport

class OpenstackAtomicOperationConverterHelper {

  static <T extends OpenstackAtomicOperationDescription> T convertDescription(Map input,
                                                                              AbstractAtomicOperationsCredentialsSupport credentialsSupport,
                                                                              Class<T> targetDescriptionType) {

    String account = input.account

    T converted = credentialsSupport.objectMapper
      .copy()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .convertValue(input, targetDescriptionType)

    converted.credentials = (OpenstackCredentials) credentialsSupport.getCredentialsObject(account)?.credentials

    converted
  }
}

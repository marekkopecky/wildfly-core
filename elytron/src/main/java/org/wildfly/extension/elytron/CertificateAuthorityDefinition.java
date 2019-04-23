/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.extension.elytron;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AbstractWriteAttributeHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.operations.validation.StringLengthValidator;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.wildfly.security.x500.cert.acme.CertificateAuthority;

import java.net.MalformedURLException;
import java.net.URL;

import static org.wildfly.extension.elytron.Capabilities.CERTIFICATE_AUTHORITY_RUNTIME_CAPABILITY;
import static org.wildfly.extension.elytron.ElytronDefinition.commonRequirements;
import static org.wildfly.extension.elytron._private.ElytronSubsystemMessages.ROOT_LOGGER;

class CertificateAuthorityDefinition extends SimpleResourceDefinition {

    static final SimpleAttributeDefinition URL = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.URL, ModelType.STRING, false)
            .setValidator(new URLValidator(false))
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition STAGING_URL = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.STAGING_URL, ModelType.STRING, true)
            .setRestartAllServices()
            .build();

    private static class URLValidator extends StringLengthValidator {

        private URLValidator(boolean nullable) {
            super(1, nullable, false);
        }

        @Override
        public void validateParameter(String parameterName, ModelNode value) throws OperationFailedException {
            super.validateParameter(parameterName, value);
            String url = value.asString();
            try {
                new URL(url);
            } catch (MalformedURLException e) {
                throw ROOT_LOGGER.invalidURL(url, e);
            }
        }
    }

    private static final AttributeDefinition[] ATTRIBUTES = new AttributeDefinition[]{URL, STAGING_URL};
    private static final AbstractAddStepHandler ADD = new CertificateAuthorityAddHandler();

    private static final OperationStepHandler REMOVE = new TrivialCapabilityServiceRemoveHandler(ADD, CERTIFICATE_AUTHORITY_RUNTIME_CAPABILITY);
    private static final AbstractWriteAttributeHandler WRITE = new ElytronReloadRequiredWriteAttributeHandler(ATTRIBUTES);

    CertificateAuthorityDefinition() {
        super(new Parameters(PathElement.pathElement(ElytronDescriptionConstants.CERTIFICATE_AUTHORITY), ElytronExtension.getResourceDescriptionResolver(ElytronDescriptionConstants.CERTIFICATE_AUTHORITY))
                .setAddHandler(ADD)
                .setRemoveHandler(REMOVE)
                .setAddRestartLevel(OperationEntry.Flag.RESTART_RESOURCE_SERVICES)
                .setRemoveRestartLevel(OperationEntry.Flag.RESTART_RESOURCE_SERVICES)
                .setCapabilities(CERTIFICATE_AUTHORITY_RUNTIME_CAPABILITY));
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        for (AttributeDefinition current : ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(current, null, WRITE);
        }
    }

    private static class CertificateAuthorityAddHandler extends BaseAddHandler {
        private CertificateAuthorityAddHandler() {
            super(CERTIFICATE_AUTHORITY_RUNTIME_CAPABILITY, ATTRIBUTES);
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
            final String certificateAuthorityName = context.getCurrentAddressValue();

            // Creation of certificate authority with name "LetsEncrypt" is not allowed, because it is used internally as a fallback.
            if (certificateAuthorityName.equalsIgnoreCase(CertificateAuthority.LETS_ENCRYPT.getName())) {
                throw ROOT_LOGGER.letsEncryptNameNotAllowed();
            }
            RuntimeCapability<Void> runtimeCapability = CERTIFICATE_AUTHORITY_RUNTIME_CAPABILITY.fromBaseCapability(context.getCurrentAddressValue());
            ServiceName certificateAuthorityServiceName = runtimeCapability.getCapabilityServiceName(CertificateAuthority.class);
            commonRequirements(installService(context, certificateAuthorityServiceName, model)).install();
        }

        protected ServiceBuilder<CertificateAuthority> installService(OperationContext context, ServiceName certificateAuthorityServiceName, ModelNode model) throws OperationFailedException {
            ServiceTarget serviceTarget = context.getServiceTarget();
            TrivialService<CertificateAuthority> certificateAuthorityTrivialService = new TrivialService<>(getValueSupplier(context, model));
            return serviceTarget.addService(certificateAuthorityServiceName, certificateAuthorityTrivialService);
        }

        protected TrivialService.ValueSupplier<CertificateAuthority> getValueSupplier(OperationContext context, ModelNode model) throws OperationFailedException {
            return () -> {
                String certificateAuthorityResourceName = context.getCurrentAddress().getLastElement().getValue();
                return new CertificateAuthority(certificateAuthorityResourceName,
                        model.get(ElytronDescriptionConstants.URL).asString(),
                        model.get(ElytronDescriptionConstants.STAGING_URL).asStringOrNull());
            };
        }
    }
}

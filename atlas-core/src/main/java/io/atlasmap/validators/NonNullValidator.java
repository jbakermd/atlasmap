/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atlasmap.validators;

import java.util.List;
import io.atlasmap.spi.AtlasValidator;
import io.atlasmap.v2.Validation;
import io.atlasmap.v2.ValidationStatus;

public class NonNullValidator implements AtlasValidator {

    private String violationMessage;
    private String field;

    public NonNullValidator(String field, String violationMessage) {
        this.field = field;
        this.violationMessage = violationMessage;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return true;
    }

    @Override
    public void validate(Object target, List<Validation> validations) {
        validate(target, validations, ValidationStatus.ERROR);
    }

    @Override
    public void validate(Object target, List<Validation> validations, ValidationStatus status) {

        if (target == null) {
            Validation validation = new Validation();
            validation.setField(field);
            validation.setMessage(violationMessage);
            validation.setStatus(status);
            validations.add(validation);
        } else if (target.getClass().isAssignableFrom(String.class)) {
            String value = (String) target;
            if (value.trim().isEmpty()) {
                // TODO: Add support for target value
                Validation validation = new Validation();
                validation.setField(field);
                validation.setMessage(violationMessage);
                validation.setStatus(status);
                validation.setValue(target.toString());
                validations.add(validation);            
            }
        }
    }
}

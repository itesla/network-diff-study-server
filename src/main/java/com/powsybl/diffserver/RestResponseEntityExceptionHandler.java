/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import static com.powsybl.diffserver.DiffStudyConstants.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */

@ControllerAdvice
public class RestResponseEntityExceptionHandler {

    @ExceptionHandler(value = {DiffStudyException.class})
    protected ResponseEntity<Object> handleException(RuntimeException ex) {
        String errorMessage = ex.getMessage();
        if (errorMessage.equals(DIFF_STUDY_DOESNT_EXISTS)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(DIFF_STUDY_DOESNT_EXISTS);
        } else if (errorMessage.equals(CASE_DOESNT_EXISTS)) {
            return ResponseEntity.status(HttpStatus.FAILED_DEPENDENCY).body(CASE_DOESNT_EXISTS);
        } else if (errorMessage.equals(DIFF_STUDY_ALREADY_EXISTS)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(DIFF_STUDY_ALREADY_EXISTS);
        } else if (errorMessage.equals(NOT_ALLOWED)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(NOT_ALLOWED);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}

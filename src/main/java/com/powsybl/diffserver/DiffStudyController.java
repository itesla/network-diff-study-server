/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import com.powsybl.diffserver.dto.CaseInfos;
import com.powsybl.diffserver.dto.DiffStudyInfos;
import com.powsybl.diffserver.dto.VoltageLevelAttributes;
import com.powsybl.diffserver.repository.DiffStudy;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
@RestController
@RequestMapping(value = "/" + DiffStudyApi.API_VERSION)
@Transactional
@Api(value = "Diff Study server")
@ComponentScan(basePackageClasses = DiffStudyService.class)
public class DiffStudyController {

    private final DiffStudyService diffStudyService;

    public DiffStudyController(DiffStudyService diffStudyService) {
        this.diffStudyService = diffStudyService;
    }

    @GetMapping(value = "/diff-studies")
    @ApiOperation(value = "Get all diff studies")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "The list of studies")})
    public ResponseEntity<Flux<DiffStudyInfos>> getDiffStudyList() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(diffStudyService.getDiffStudyList());
    }

    @PostMapping(value = "/diff-studies/{diffStudyName}/study/{case1Uuid}/{case2Uuid}")
    @ApiOperation(value = "create a diff study from two existing cases")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "The id of the network imported"),
            @ApiResponse(code = 409, message = "The diff study already exist or the cases do not exists")})
    public ResponseEntity<Mono<Void>> createStudyFromExistingCase(@PathVariable("diffStudyName") String diffStudyName,
                                                                  @PathVariable("case1Uuid") UUID case1Uuid,
                                                                  @PathVariable("case2Uuid") UUID case2Uuid,
                                                                  @RequestParam("description") String description) {
        return ResponseEntity.ok().body(Mono.when(diffStudyService.assertDiffStudyNotExists(diffStudyName), diffStudyService.assertCaseExists(case1Uuid), diffStudyService.assertCaseExists(case2Uuid))
                .then(diffStudyService.createDiffStudy(diffStudyName, case1Uuid, case2Uuid, description).then()));
    }

    @GetMapping(value = "/diff-studies/{diffStudyName}")
    @ApiOperation(value = "get a diff study")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "The study information"),
            @ApiResponse(code = 404, message = "The study doesn't exist")})
    public ResponseEntity<Mono<DiffStudy>> getStudy(@PathVariable("diffStudyName") String diffStudyName) {
        Mono<DiffStudy> studyMono = diffStudyService.getDiffStudy(diffStudyName);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(studyMono.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND))).then(studyMono));
    }

    @GetMapping(value = "/diff-studies/{diffStudyName}/exists")
    @ApiOperation(value = "Check if the diff study exists", produces = "application/json")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "If the study exists or not.")})
    public ResponseEntity<Mono<Boolean>> studyExists(@PathVariable("diffStudyName") String diffStudyName) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(diffStudyService.diffStudyExists(diffStudyName));
    }

    @DeleteMapping(value = "/diff-studies/{diffStudyName}")
    @ApiOperation(value = "delete the diff study")
    @ApiResponse(code = 200, message = "Diff study deleted")
    public ResponseEntity<Mono<Void>> deleteStudy(@PathVariable("diffStudyName") String diffStudyName) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(diffStudyService.deleteDiffStudy(diffStudyName).then());
    }

    @GetMapping(value = "diff-studies/{diffStudyName}/voltage-levels")
    @ApiOperation(value = "get the voltage levels for a given diff study case")
    @ApiResponse(code = 200, message = "The voltage level list")
    public ResponseEntity<Mono<List<VoltageLevelAttributes>>> getNetworkVoltageLevels(
            @PathVariable("diffStudyName") String diffStudyName) {
        DiffStudy diffStudy = diffStudyService.getDiffStudy(diffStudyName).block();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(diffStudyService.getDiffStudyVoltageLevels(diffStudy));
    }

    @GetMapping(value = "diff-studies/{diffStudyName}/voltage-level-diff")
    @ApiOperation(value = "compare a voltage level between the two diffStudy's networks")
    @ApiResponse(code = 200, message = "The voltage level diff")
    public ResponseEntity<Mono<String>> getVoltageLevelDiff(
            @PathVariable("diffStudyName") String diffStudyName,
            @RequestParam("voltageLevelId") String voltageLevelId) {
        DiffStudy diffStudy = diffStudyService.getDiffStudy(diffStudyName).block();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(
                diffStudyService.getDiffVoltageLevel(diffStudy, voltageLevelId)
        );
    }

    @GetMapping(value = "/diff-studies/casesmetadata")
    @ApiOperation(value = "Get all cases metadata")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "The list of studies")})
    public ResponseEntity<Flux<Map>> getCasesMetadata() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(diffStudyService.getCasesMetadata());
    }

    @PostMapping(value = "/diff-studies/{diffStudyName}/zone")
    @ApiOperation(value = "Set the zone information for the diff-study", produces = "application/json")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "The updated study")})
    public ResponseEntity<Mono<DiffStudy>> setZone(@PathVariable("diffStudyName") String diffStudyName,
                                                   @RequestParam("zone") List<String> zone) {
        Mono<DiffStudy> diffStudy = diffStudyService.getDiffStudy(diffStudyName);
        Mono<List<String>> noMatchingSubstations = diffStudyService.getNoMatchingSubstations(diffStudyName, zone);

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(
        Mono.zip(diffStudy, noMatchingSubstations)
                .filter(s -> s.getT2().isEmpty())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "substations not found: " + noMatchingSubstations.block().toString())))
                .then(diffStudyService.setZone(diffStudyName, zone)));
    }

    @PostMapping(value = "/diff-studies/{diffStudyName}/description")
    @ApiOperation(value = "Set the description of the diff-study", produces = "application/json")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "The updated study")})
    public ResponseEntity<Mono<DiffStudy>> setDescription(@PathVariable("diffStudyName") String diffStudyName,
                                                   @RequestParam("description") String description) {
        Mono<DiffStudy> studyMono = diffStudyService.setDescription(diffStudyName, description);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(studyMono);
    }

    @GetMapping(value = "/diff-studies/searchcase")
    @ApiOperation(value = "Search cases", produces = "application/json")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "cases matching query")})
    public ResponseEntity<Flux<CaseInfos>> searchCase(@RequestParam("q") String q) {
        Flux<CaseInfos> cases = diffStudyService.searchCase(q);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(cases);
    }

}

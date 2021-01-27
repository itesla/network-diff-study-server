/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import com.powsybl.commons.PowsyblException;
import com.powsybl.diffserver.dto.CaseInfos;
import com.powsybl.diffserver.dto.DiffStudyInfos;
import com.powsybl.diffserver.dto.NetworkInfos;
import com.powsybl.diffserver.dto.VoltageLevelAttributes;
import com.powsybl.diffserver.repository.DiffStudy;
import com.powsybl.diffserver.repository.DiffStudyRepository;
import com.powsybl.network.store.client.NetworkStoreService;
import com.powsybl.network.store.model.TopLevelDocument;
import org.gridsuite.geodata.server.dto.SubstationGeoData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.powsybl.diffserver.DiffStudyConstants.*;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */

@ComponentScan(basePackageClasses = {NetworkStoreService.class, DiffStudyRepository.class})
@Service
public class DiffStudyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiffStudyService.class);

    private WebClient webClient;

    String caseServerBaseUri;
    String networkConversionServerBaseUri;
    String networkStoreServerBaseUri;
    String networkDiffServerBaseUri;
    String geoServerBaseUri;

    private final DiffStudyRepository diffStudyRepository;

    @Autowired
    public DiffStudyService(
            @Value("${network-store-server.base-uri:http://network-store-server/}") String networkStoreServerBaseUri,
            @Value("${backing-services.case.base-uri:http://case-server/}") String caseServerBaseUri,
            @Value("${backing-services.network-conversion.base-uri:http://network-conversion-server/}") String networkConversionServerBaseUri,
            @Value("${backing-services.network-diff.base-uri:http://network-diff-server/}") String networkDiffServerBaseUri,
            @Value("${backing-services.geo-server.base-uri:http://geo-data-server/}") String geoServerBaseUri,
            DiffStudyRepository studyRepository,
            WebClient.Builder webClientBuilder) {
        this.caseServerBaseUri = caseServerBaseUri;
        this.networkConversionServerBaseUri = networkConversionServerBaseUri;
        this.networkStoreServerBaseUri = networkStoreServerBaseUri;
        this.networkDiffServerBaseUri = networkDiffServerBaseUri;
        this.geoServerBaseUri = geoServerBaseUri;

        this.webClient =  webClientBuilder.build();

        this.diffStudyRepository = studyRepository;
    }

    Flux<DiffStudyInfos> getDiffStudyList() {
        Flux<DiffStudy> diffStudyFlux = diffStudyRepository.findAll();
        return diffStudyFlux.map(diffStudy ->
                new DiffStudyInfos(diffStudy.getDiffStudyName(), diffStudy.getDescription(), diffStudy.getNetwork1Id(), diffStudy.getCase1Format(), diffStudy.getNetwork2Id(), diffStudy.getCase2Format(), diffStudy.getZone().toArray(String[]::new))
        );
    }

    @Transactional
    public Mono<DiffStudy> createDiffStudy(String diffStudyName, UUID case1Uuid, UUID case2Uuid, String description) {
        Mono<NetworkInfos> network1Infos = persistentStore(case1Uuid);
        Mono<NetworkInfos> network2Infos = persistentStore(case2Uuid);
        Mono<String> case1Format = getCaseFormat(case1Uuid);
        Mono<String> case2Format = getCaseFormat(case2Uuid);
        return Mono.zip(network1Infos, case1Format, network2Infos, case2Format)
                .log()
                .flatMap(t -> {
                    return insertDiffStudy(diffStudyName, t.getT1().getNetworkUuid(), t.getT1().getNetworkId(), t.getT3().getNetworkUuid(),
                            t.getT3().getNetworkId(), description, case1Uuid, t.getT2(), case2Uuid, t.getT4());
                })
                .onErrorMap(t -> {
                    LOGGER.error(t.getMessage(), t);
                    return new RuntimeException(t);
                });
    }

    private Mono<String> getCaseFormat(UUID caseUuid) {
        String path = UriComponentsBuilder.fromPath(DELIMITER + CASE_API_VERSION + "/cases/{caseUuid}/format")
                .buildAndExpand(caseUuid)
                .toUriString();

        return webClient.get()
                .uri(caseServerBaseUri + path)
                .retrieve()
                .bodyToMono(String.class);
    }

    @Transactional
    public Mono<DiffStudy> createDiffStudy(String diffStudyName, Mono<FilePart> case1File, Mono<FilePart> case2File, String description) {
        Mono<UUID> case1UUid = importCase(case1File);
        Mono<UUID> case2UUid = importCase(case1File);

        return Mono.zip(case1UUid, case2UUid).flatMap(ct -> {
            Mono<NetworkInfos> network1Infos = persistentStore(ct.getT1());
            Mono<NetworkInfos> network2Infos = persistentStore(ct.getT2());
            Mono<String> case1Format = getCaseFormat(ct.getT1());
            Mono<String> case2Format = getCaseFormat(ct.getT2());
            return Mono.zip(network1Infos, network2Infos, case1Format, case2Format)
                    .flatMap(t ->
                            insertDiffStudy(diffStudyName,
                                    t.getT1().getNetworkUuid(), t.getT1().getNetworkId(),
                                    t.getT2().getNetworkUuid(), t.getT1().getNetworkId(),
                                    description,
                                    ct.getT1(),
                                    t.getT3(),
                                    ct.getT2(),
                                    t.getT4()
                                    )
                    );
        });
    }

    private Mono<DiffStudy> insertDiffStudy(String diffStudyName, UUID network1Uuid, String network1Id, UUID network2Uuid, String network2Id, String description, UUID case1Uuid, String case1Format, UUID case2Uuid, String case2format) {
        LOGGER.info("insertDiffStudy {} {} {} {} {} {} {} {} {} {}", diffStudyName, network1Uuid, network1Id, network2Uuid, network2Id, description, case1Uuid, case1Format, case2Uuid, case2format);
        final DiffStudy diffStudy = new DiffStudy(diffStudyName, description, network1Uuid, network1Id, network2Uuid, network2Id, case1Uuid, case1Format, case2Uuid, case2format, new ArrayList<String>());
        return diffStudyRepository.insert(diffStudy);
    }

    Mono<DiffStudy> getDiffStudy(String diffStudyName) {
        return diffStudyRepository.findByDiffStudyName(diffStudyName);
    }

    @Transactional
    public Mono<Void> deleteDiffStudy(String diffStudyName) {
        Mono<DiffStudy> studyMono = diffStudyRepository.findByDiffStudyName(diffStudyName);
        return studyMono.switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .flatMap(study ->
                        Mono.zip(deleteNetwork(study.getNetwork1Uuid()),
                                deleteNetwork(study.getNetwork2Uuid()))
                                .then(diffStudyRepository.delete(study))
                );
    }

    Mono<UUID> importCase(Mono<FilePart> multipartFile) {
        return multipartFile.flatMap(file -> {
            MultipartBodyBuilder multipartBodyBuilder = new MultipartBodyBuilder();
            multipartBodyBuilder.part("file", file);

            return webClient.post()
                    .uri(caseServerBaseUri + "/" + CASE_API_VERSION + "/cases/public")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.MULTIPART_FORM_DATA.toString())
                    .body(BodyInserters.fromMultipartData(multipartBodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(UUID.class);
        });
    }

    private Mono<NetworkInfos> persistentStore(UUID caseUuid) {
        String path = UriComponentsBuilder.fromPath(DELIMITER + NETWORK_CONVERSION_API_VERSION + "/networks")
                .queryParam(CASE_UUID, caseUuid)
                .buildAndExpand()
                .toUriString();

        return webClient.post()
                .uri(networkConversionServerBaseUri + path)
                .retrieve()
                .bodyToMono(NetworkInfos.class);
    }

    private Mono<NetworkInfos> persistentStoreFake(UUID caseUuid) {
        UUID fakeNetworkUuid = UUID.randomUUID();
        NetworkInfos netinfo = new NetworkInfos(fakeNetworkUuid, "fake - " + fakeNetworkUuid + "(for caseUuid " + caseUuid + ")");
        Scheduler singleThread = Schedulers.single();
        return Mono.just(netinfo).publishOn(singleThread).map(n -> {
            try {
                TimeUnit.SECONDS.sleep(20);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return netinfo;
        });
    }

    // This functions call directly the network store server without using the dedicated client because it's a blocking client.
    Mono<List<VoltageLevelAttributes>> getNetworkVoltageLevels(UUID networkUuid) {
        String path = UriComponentsBuilder.fromPath("v1/networks/{networkId}/voltage-levels")
                .buildAndExpand(networkUuid)
                .toUriString();

        Mono<TopLevelDocument<com.powsybl.network.store.model.VoltageLevelAttributes>> mono = webClient.get()
                .uri(networkStoreServerBaseUri + path)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<TopLevelDocument<com.powsybl.network.store.model.VoltageLevelAttributes>>() { });

        return mono.map(t -> t.getData().stream().map(e -> new VoltageLevelAttributes(e.getId(), e.getAttributes().getName(), e.getAttributes().getSubstationId())).collect(Collectors.toList()));
    }

    Mono<List<String>> getNetworkSubstationsIds(UUID networkUuid) {
        String path = UriComponentsBuilder.fromPath("v1/networks/{networkId}/substations")
                .buildAndExpand(networkUuid)
                .toUriString();

        Mono<TopLevelDocument<com.powsybl.network.store.model.SubstationAttributes>> mono = webClient.get()
                .uri(networkStoreServerBaseUri + path)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<TopLevelDocument<com.powsybl.network.store.model.SubstationAttributes>>() { });

        return mono.map(t -> t.getData().stream().map(e -> e.getId()).distinct().collect(Collectors.toList()));
    }

    public Mono<List<VoltageLevelAttributes>> getDiffStudyVoltageLevels(DiffStudy diffStudy) {
        return Mono.zip(getNetworkVoltageLevels(diffStudy.getNetwork1Uuid()),
                getNetworkVoltageLevels(diffStudy.getNetwork2Uuid()))
                .flatMap(t -> {
                    //should use contains on VoltageLevelAttributes, though
                    return Mono.just(t.getT1().stream().filter(os -> t.getT2().stream()
                            .anyMatch(ns -> os.getId().equals(ns.getId()) && os.getSubstationId().equals(ns.getSubstationId())))
                            .collect(Collectors.toList()));
                });
    }

    private Mono<Void> deleteNetwork(UUID networkUuid) {
        String path = UriComponentsBuilder.fromPath("v1/networks/{networkId}")
                .buildAndExpand(networkUuid)
                .toUriString();
        return webClient.delete()
                .uri(networkStoreServerBaseUri + path)
                .retrieve()
                .bodyToMono(Void.class);
    }

    Mono<Boolean> caseExists(UUID caseUuid) {
        String path = UriComponentsBuilder.fromPath(DELIMITER + CASE_API_VERSION + "/cases/{caseUuid}/exists")
                .buildAndExpand(caseUuid)
                .toUriString();

        return webClient.get()
                .uri(caseServerBaseUri + path)
                .retrieve()
                .bodyToMono(Boolean.class);
    }

    Mono<UUID> getDiffStudyUuid(String diffStudyName) {
        Mono<DiffStudy> studyMono = diffStudyRepository.findByDiffStudyName(diffStudyName);
        //should be tested also the Network2Uuid
        return studyMono.map(DiffStudy::getNetwork1Uuid)
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)));

    }

    Mono<Boolean> diffStudyExists(String diffStudyName) {
        return getDiffStudy(diffStudyName).hasElement();
    }

    public Mono<Void> assertCaseExists(UUID caseUuid) {
        Mono<Boolean> caseExists = caseExists(caseUuid);
        return caseExists.flatMap(c -> (boolean) c ? Mono.empty() : Mono.error(new DiffStudyException(CASE_DOESNT_EXISTS)));
    }

    public Mono<Void> assertDiffStudyNotExists(String diffStudyName) {
        Mono<Boolean> studyExists = diffStudyExists(diffStudyName);
        return studyExists.flatMap(s -> (boolean) s ? Mono.error(new DiffStudyException(DIFF_STUDY_ALREADY_EXISTS)) : Mono.empty());
    }

    void setCaseServerBaseUri(String caseServerBaseUri) {
        this.caseServerBaseUri = caseServerBaseUri;
    }

    void setNetworkConversionServerBaseUri(String networkConversionServerBaseUri) {
        this.networkConversionServerBaseUri = networkConversionServerBaseUri;
    }

    void setNetworkStoreServerBaseUri(String networkStoreServerBaseUri) {
        this.networkStoreServerBaseUri = networkStoreServerBaseUri + DELIMITER;
    }

    public Mono<String> getDiffVoltageLevel(DiffStudy diffStudy, String voltageLevelId) {
        String path = UriComponentsBuilder.fromPath(DELIMITER + CASE_API_VERSION + "/networks/{network1Uuid}/diff/{network2Uuid}/vl/{vlId}")
                .buildAndExpand(diffStudy.getNetwork1Uuid(), diffStudy.getNetwork2Uuid(), voltageLevelId)
                .toUriString();
        return webClient.get()
                .uri(networkDiffServerBaseUri + path)
                .retrieve()
                .bodyToMono(String.class);
    }

    public  Flux<Map> getCasesMetadata() {
        String path = UriComponentsBuilder.fromPath("/v1/cases")
                .toUriString();

        return webClient.get()
                .uri(caseServerBaseUri + path)
                .retrieve()
                .bodyToFlux(Map.class);
    }

    public Mono<DiffStudy> setZone(String diffStudyName, List<String> zone) {
        Mono<DiffStudy> diffStudyMono = diffStudyRepository.findByDiffStudyName(diffStudyName);
        return diffStudyMono
                .log()
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .flatMap(s -> {
                    s.setZone(zone);
                    return diffStudyRepository.save(s);
                });
    }

    public Mono<DiffStudy> setDescription(String diffStudyName, String description) {
        Mono<DiffStudy> diffStudyMono = diffStudyRepository.findByDiffStudyName(diffStudyName);
        return diffStudyMono
                .log()
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .flatMap(s -> {
                    s.setDescription(description);
                    return diffStudyRepository.save(s);
                });
    }

    public Mono<List<String>> getSubstationsIds(String diffStudyName) {
        return getDiffStudy(diffStudyName)
                .flatMap(s -> getDiffStudyVoltageLevels(s))
                .flatMapIterable(Function.identity())
                .map(s -> s.getSubstationId())
                .sort()
                .distinct()
                .collectList();
    }

    public Mono<List<String>> getNoMatchingSubstations(String diffStudyName, List<String> zone) {
        List<String> subsIds = getSubstationsIds(diffStudyName).block();
        return Mono.just(zone.stream().filter(zs -> !subsIds.contains(zs)).collect(Collectors.toList()));
    }

    public Flux<CaseInfos> searchCase(String query) {
        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new PowsyblException("Error when encoding the query string");
        }

        String path = UriComponentsBuilder.fromPath("/v1/cases/search?q={query}")
                .buildAndExpand(encodedQuery)
                .toUriString();

        LOGGER.info("search query: {}", path);

        Flux<Map> results = webClient.get()
                .uri(caseServerBaseUri + path)
                .retrieve()
                .bodyToFlux(Map.class);

        return results.map(result -> {
            LOGGER.info("search results: ", result);
            return new CaseInfos(result.get("name").toString(), result.get("format").toString(),
                UUID.fromString(result.get("uuid").toString()),
                ZonedDateTime.now(),
                0);
        });

    }

    public Mono<List<SubstationGeoData>> getSubsCoordinates(UUID networkUuid) {

        String path = UriComponentsBuilder.fromPath("/v1/substations?networkUuid={networkUuid}")
                .buildAndExpand(networkUuid)
                .toUriString();

        LOGGER.info("getgeodata substations for network: {}", networkUuid);

        SubstationGeoData[] results = webClient.get()
                .uri(geoServerBaseUri + path)
                .retrieve()
                .bodyToMono(SubstationGeoData[].class).block();

        return Mono.just(Arrays.asList(results));
    }
}

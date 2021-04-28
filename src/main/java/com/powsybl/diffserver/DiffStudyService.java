/**
 * Copyright (c) 2020-2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.LineString;
import com.mapbox.geojson.Point;
import com.powsybl.commons.PowsyblException;
import com.powsybl.diffserver.dto.CaseInfos;
import com.powsybl.diffserver.dto.DiffStudyInfos;
import com.powsybl.diffserver.dto.NetworkInfos;
import com.powsybl.diffserver.dto.VoltageLevelAttributes;
import com.powsybl.diffserver.repository.DiffStudy;
import com.powsybl.diffserver.repository.DiffStudyRepository;
import com.powsybl.iidm.diff.*;
import com.powsybl.iidm.network.*;
import com.powsybl.network.store.client.NetworkStoreService;
import com.powsybl.network.store.client.PreloadingStrategy;
import com.powsybl.network.store.model.TopLevelDocument;
import org.gridsuite.geodata.extensions.Coordinate;
import org.gridsuite.geodata.server.dto.LineGeoData;
import org.gridsuite.geodata.server.dto.SubstationGeoData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    class LineDiffData {
        final String lineId;
        final String pDelta1;
        final String qDelta1;
        final String iDelta1;
        final String pDelta2;
        final String qDelta2;
        final String iDelta2;

        public LineDiffData(String lineId, String pDelta1, String qDelta1, String iDelta1, String pDelta2, String qDelta2, String iDelta2) {
            this.lineId = lineId;
            this.pDelta1 = pDelta1;
            this.qDelta1 = qDelta1;
            this.iDelta1 = iDelta1;
            this.pDelta2 = pDelta2;
            this.qDelta2 = qDelta2;
            this.iDelta2 = iDelta2;
        }

        public String getLineId() {
            return lineId;
        }

        public String getpDelta1() {
            return pDelta1;
        }

        public String getqDelta1() {
            return qDelta1;
        }

        public String getiDelta1() {
            return iDelta1;
        }

        public String getpDelta2() {
            return pDelta2;
        }

        public String getqDelta2() {
            return qDelta2;
        }

        public String getiDelta2() {
            return iDelta2;
        }

        @Override
        public String toString() {
            return "LineDiffData{" +
                    "lineId='" + lineId + '\'' +
                    '}';
        }
    }

    class VlDiffData {
        final String vlId;
        final String minVDelta;
        final String maxVDelta;

        public VlDiffData(String vlId, String minVDelta, String maxVDelta) {
            this.vlId = vlId;
            this.minVDelta = minVDelta;
            this.maxVDelta = maxVDelta;
        }

        public String getVlId() {
            return vlId;
        }

        public String getMinVDelta() {
            return minVDelta;
        }

        public String getMaxVDelta() {
            return maxVDelta;
        }

        @Override
        public String toString() {
            return "VlDiffData{" +
                    "vlId='" + vlId + '\'' +
                    ", minVDelta='" + minVDelta + '\'' +
                    ", maxVDelta='" + maxVDelta + '\'' +
                    '}';
        }
    }

    class DiffData {
        final List<String> switchesDiff;
        final List<String> branchesDiff;
        final List<LineDiffData> linesDiffData;
        final Map<String, VlDiffData> vlDiffData;

        DiffData(String jsonDiff) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> jsonMap = objectMapper.readValue(jsonDiff, new TypeReference<Map<String, Object>>() {
                });
                switchesDiff = (List<String>) ((List) jsonMap.get("diff.VoltageLevels")).stream()
                        .map(t -> ((Map) t).get("vl.switchesStatus-delta"))
                        .flatMap(t -> ((List<String>) t).stream())
                        .collect(Collectors.toList());
                branchesDiff = (List<String>) ((List) jsonMap.get("diff.Branches")).stream()
                        .map(t -> ((Map) t).get("branch.terminalStatus-delta"))
                        .flatMap(t -> ((List<String>) t).stream())
                        .collect(Collectors.toList());
                linesDiffData = (List<LineDiffData>) ((List) jsonMap.get("diff.Branches")).stream()
                        .map(t -> {
                            Map<String, Object> branchMap = (Map) t;
                            return new LineDiffData(branchMap.get("branch.branchId1").toString(),
                                    branchMap.get("branch.terminal1.p-delta").toString(),
                                    branchMap.get("branch.terminal1.q-delta").toString(),
                                    branchMap.get("branch.terminal1.i-delta").toString(),
                                    branchMap.get("branch.terminal2.p-delta").toString(),
                                    branchMap.get("branch.terminal2.q-delta").toString(),
                                    branchMap.get("branch.terminal2.i-delta").toString());
                        })
                        .collect(Collectors.toList());

                vlDiffData = (Map<String, VlDiffData>) ((List) jsonMap.get("diff.VoltageLevels")).stream()
                        .map(t -> {
                            Map<String, Object> vlMap = (Map) t;
                            return new VlDiffData(vlMap.get("vl.vlId1").toString(),
                                    vlMap.get("vl.minV-delta").toString(),
                                    vlMap.get("vl.maxV-delta").toString());
                        }).collect(Collectors.toMap(VlDiffData::getVlId, vlDiffData -> vlDiffData));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public List<String> getSwitchesIds() {
            return switchesDiff;
        }

        public List<String> getBranchesIds() {
            return branchesDiff;
        }

        public List<LineDiffData> getLinesDiffData() {
            return linesDiffData;
        }

        public Map<String, VlDiffData> getVlDiffData() {
            return vlDiffData;
        }
    }

    private WebClient webClient;

    String caseServerBaseUri;
    String networkConversionServerBaseUri;
    String networkStoreServerBaseUri;
    String networkDiffServerBaseUri;
    String geoServerBaseUri;

    private final DiffStudyRepository diffStudyRepository;

    private final String emptyGeoJson = FeatureCollection.fromFeatures(Collections.emptyList()).toJson();

    @Autowired
    private NetworkStoreService networkStoreService;

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

        this.webClient = webClientBuilder.build();

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
                .bodyToMono(new ParameterizedTypeReference<TopLevelDocument<com.powsybl.network.store.model.VoltageLevelAttributes>>() {
                });

        return mono.map(t -> t.getData().stream().map(e -> new VoltageLevelAttributes(e.getId(), e.getAttributes().getName(), e.getAttributes().getSubstationId())).collect(Collectors.toList()));
    }

    Mono<List<String>> getNetworkSubstationsIds(UUID networkUuid) {
        String path = UriComponentsBuilder.fromPath("v1/networks/{networkId}/substations")
                .buildAndExpand(networkUuid)
                .toUriString();

        Mono<TopLevelDocument<com.powsybl.network.store.model.SubstationAttributes>> mono = webClient.get()
                .uri(networkStoreServerBaseUri + path)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<TopLevelDocument<com.powsybl.network.store.model.SubstationAttributes>>() {
                });

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

    public Flux<Map> getCasesMetadata() {
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

    public SubstationGeoData[] getSubsCoordinates(UUID networkUuid) {

        String path = UriComponentsBuilder.fromPath("/v1/substations?networkUuid={networkUuid}")
                .buildAndExpand(networkUuid)
                .toUriString();

        LOGGER.info("getgeodata substations for network: {}", networkUuid);

        SubstationGeoData[] results = webClient.get()
                .uri(geoServerBaseUri + path)
                .retrieve()
                .bodyToMono(SubstationGeoData[].class).block();

        return results;
    }

    private Network getNetwork(UUID networkUuid) {
        try {
            return networkStoreService.getNetwork(networkUuid, PreloadingStrategy.NONE);
        } catch (PowsyblException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Network '" + networkUuid + "' not found");
        }
    }

    public List<String> getZoneLines(UUID networkUuid, List<String> zone) {
        try {
            Network network = getNetwork(networkUuid);
            List<String> zoneLines = zone.stream().map(s -> network.getSubstation(s).getVoltageLevelStream().flatMap(vl -> vl.getConnectableStream(Line.class))
                    .map(Line::getId).collect(Collectors.toList())).flatMap(List::stream).distinct().collect(Collectors.toList());
            return zoneLines;
        } catch (PowsyblException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Network '" + networkUuid + "' not found");
        }
    }

    private LineGeoData[] getLinesCoordinates(UUID networkUuid) {
        String path = UriComponentsBuilder.fromPath("/v1/lines?networkUuid={networkUuid}")
                .buildAndExpand(networkUuid)
                .toUriString();

        LOGGER.info("getgeodata lines for network: {}", networkUuid);

        LineGeoData[] results = webClient.get()
                .uri(geoServerBaseUri + path)
                .retrieve()
                .bodyToMono(LineGeoData[].class).block();
        return results;
    }

    public List<LineGeoData> getLinesCoordinatesList(UUID networkUuid) {
        return Arrays.asList(getLinesCoordinates(networkUuid));
    }

    public Map<String, LineGeoData> getLinesCoordinatesAsMap(UUID networkUuid) {
        return Arrays.stream(getLinesCoordinates(networkUuid))
                .collect(Collectors.toMap(LineGeoData::getId, geoData -> geoData));
    }

    private Map<String, LineGeoData> getLinesCoordinatesConnectingSubstationsAsMap(Network network, List<String> zoneLines, Map<String, SubstationGeoData> subsCoordsMap) {
        Map<String, LineGeoData> map = new HashMap<>();
        for (String zoneLineId : zoneLines) {
            String subId1 = network.getLine(zoneLineId).getTerminal1().getVoltageLevel().getSubstation().getId();
            Optional<Country> subCountry1 = network.getLine(zoneLineId).getTerminal1().getVoltageLevel().getSubstation().getCountry();
            String subId2 = network.getLine(zoneLineId).getTerminal2().getVoltageLevel().getSubstation().getId();
            Optional<Country> subCountry2 = network.getLine(zoneLineId).getTerminal2().getVoltageLevel().getSubstation().getCountry();
            SubstationGeoData subData1 = subsCoordsMap.get(subId1);
            SubstationGeoData subData2 = subsCoordsMap.get(subId2);
            if (subData1 != null && subData2 != null && !subData1.getId().equals(subData2.getId())) {
                map.put(zoneLineId, new LineGeoData(zoneLineId, subCountry1.orElse(Country.FR), subCountry2.orElse(Country.FR),
                        List.of(subData1.getCoordinate(), subData2.getCoordinate())));
            }
        }
        return map;
    }

    private String diffVoltageLevels(Network network1, Network network2, List<String> voltageLevels, List<String> branches) {
        return diffVoltageLevels(network1, network2, voltageLevels, branches, DiffConfig.EPSILON_DEFAULT);
    }

    private String diffVoltageLevels(Network network1, Network network2, List<String> voltageLevels, List<String> branches, double threshold) {
        DiffEquipment diffEquipment = new DiffEquipment();
        diffEquipment.setVoltageLevels(voltageLevels);
        List<DiffEquipmentType> equipmentTypes = new ArrayList<DiffEquipmentType>();
        equipmentTypes.add(DiffEquipmentType.VOLTAGE_LEVELS);
        if (!branches.isEmpty()) {
            equipmentTypes.add(DiffEquipmentType.BRANCHES);
            diffEquipment.setBranches(branches);
        }
        diffEquipment.setEquipmentTypes(equipmentTypes);
        DiffConfig config = new DiffConfig(threshold, DiffConfig.FILTER_DIFF_DEFAULT);
        NetworkDiff ndiff = new NetworkDiff(config);
        NetworkDiffResults diffVl = ndiff.diff(network1, network2, diffEquipment);
        String jsonDiff = NetworkDiff.writeJson(diffVl);
        //NaN is not part of the JSON standard and frontend would fail when parsing it
        //it should be handled at the source, though
        jsonDiff = jsonDiff.replace(": NaN,", ": \"Nan\",");
        return jsonDiff;
    }

    private String diffSubstation(Network network1, Network network2, String substationId) {
        return diffSubstation(network1, network2, substationId, DiffConfig.EPSILON_DEFAULT);
    }

    private String diffSubstation(Network network1, Network network2, String substationId, double threshold) {
        Substation substation1 = network1.getSubstation(substationId);
        List<String> voltageLevels = substation1.getVoltageLevelStream().map(VoltageLevel::getId)
                .collect(Collectors.toList());
        List<String> branches = substation1.getVoltageLevelStream().flatMap(vl -> vl.getConnectableStream(Line.class))
                .map(Line::getId).collect(Collectors.toList());
        List<String> twts = substation1.getTwoWindingsTransformerStream().map(TwoWindingsTransformer::getId)
                .collect(Collectors.toList());
        branches.addAll(twts);
        String jsonDiff = diffVoltageLevels(network1, network2, voltageLevels, branches, threshold);
        return jsonDiff;
    }

    private Map<String, SubstationGeoData> getSubsCoordinatesAsMap(UUID networkUuid) {
        return Arrays.stream(getSubsCoordinates(networkUuid))
                .collect(Collectors.toMap(SubstationGeoData::getId, geoData -> geoData));
    }

    private List<SubstationGeoData> getSubsGeoData(DiffStudy diffStudy) {
        List<String> subsIds = diffStudy.getZone();
        if (!subsIds.isEmpty()) {
            Map<String, SubstationGeoData> mapSubGeodata = getSubsCoordinatesAsMap(diffStudy.getNetwork1Uuid());
            return subsIds.stream().filter(mapSubGeodata::containsKey).map(mapSubGeodata::get).collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    public List<SubstationGeoData> getSubsGeoData(String diffStudyName) {
        DiffStudy diffStudy = getDiffStudy(diffStudyName).block();
        return getSubsGeoData(diffStudy);
    }

    public String getGeoJsonLayers(String diffStudyName) {
        return getGeoJsonLayers(diffStudyName, DiffConfig.EPSILON_DEFAULT);
    }

    public String getGeoJsonLayers(String diffStudyName, double threshold) {
        JsonObject retJson = new JsonObject();
        JsonArray jsonArray = new JsonArray();

        DiffStudy diffStudy = getDiffStudy(diffStudyName).block();
        List<String> subsIds = diffStudy.getZone();
        if (!subsIds.isEmpty()) {
            Network network1 = getNetwork(diffStudy.getNetwork1Uuid());
            Network network2 = getNetwork(diffStudy.getNetwork2Uuid());

            // map subId, diffData
            Map<String, DiffData> subsDiffs = new HashMap<>();
            for (String subId : subsIds) {
                String jsonDiff = diffSubstation(network1, network2, subId, threshold);
                DiffData diffData = new DiffData(jsonDiff);
                subsDiffs.put(subId, diffData);
            }

            //subs geoJson
            // map subId, substationGeoData
            Map<String, SubstationGeoData> allSubsGeodata = getSubsCoordinatesAsMap(diffStudy.getNetwork1Uuid());
            Map<String, SubstationGeoData> zoneSubsGeodata = subsIds.stream().filter(allSubsGeodata::containsKey).map(allSubsGeodata::get).collect(Collectors.toMap(SubstationGeoData::getId, t -> t));
            String subsgeoJson = extractJsonSubs(subsIds, subsDiffs, zoneSubsGeodata, network1);
            JsonObject layerObj = new JsonObject();
            layerObj.addProperty("name", "SUBS");
            layerObj.addProperty("data", subsgeoJson);
            jsonArray.add(layerObj);

            // lines geoJson (true coordinates) note: retrieve linesGeoData for all the network lines is quite expensive
            Map<String, LineGeoData> networkLinesCoordsData = getLinesCoordinatesAsMap(diffStudy.getNetwork1Uuid());
            List<String> zoneLines = getZoneLines(diffStudy.getNetwork1Uuid(), diffStudy.getZone());
            String linesGeoJson = extractJsonLines(subsIds, networkLinesCoordsData, subsDiffs, zoneLines);
            layerObj = new JsonObject();
            layerObj.addProperty("name", "LINES");
            layerObj.addProperty("data", linesGeoJson);
            jsonArray.add(layerObj);

            //simple lines (connecting substations)
            Map<String, LineGeoData> networkLinesCoordsData2 = getLinesCoordinatesConnectingSubstationsAsMap(network1, zoneLines,
                    getSubsCoordinatesAsMap(diffStudy.getNetwork1Uuid()));
            String simpleLinesGeoJson = extractJsonLines(subsIds, networkLinesCoordsData2, subsDiffs, zoneLines);
            layerObj = new JsonObject();
            layerObj.addProperty("name", "LINES-SIMPLE");
            layerObj.addProperty("data", simpleLinesGeoJson);
            jsonArray.add(layerObj);
        } else {
            //return empty layers
            JsonObject layerObj = new JsonObject();
            layerObj.addProperty("name", "SUBS");
            layerObj.addProperty("data", emptyGeoJson);
            jsonArray.add(layerObj);

            layerObj = new JsonObject();
            layerObj.addProperty("name", "LINES");
            layerObj.addProperty("data", emptyGeoJson);
            jsonArray.add(layerObj);

            layerObj = new JsonObject();
            layerObj.addProperty("name", "LINES-SIMPLE");
            layerObj.addProperty("data", emptyGeoJson);
            jsonArray.add(layerObj);
        }

        retJson.add("layers", jsonArray);
        return retJson.toString();
    }

    private String extractJsonSubs(List<String> subsIds, Map<String, DiffData> subsDiffs, Map<String, SubstationGeoData> zoneSubsGeodata, Network network1) {
        List<Feature> features = new ArrayList<>();
        for (String subId : subsIds) {
            DiffData diffData = subsDiffs.get(subId);
            SubstationGeoData subData = zoneSubsGeodata.get(subId);
            AtomicBoolean isSubDifferent = new AtomicBoolean(false);
            if (subData != null) {
                //sub data
                Substation substation = network1.getSubstation(subId);

                Map<String, VlDiffData> vlDiffDataMap = diffData.getVlDiffData();
                Coordinate subCoords = subData.getCoordinate();
                List<String> subVlevelsIds = substation.getVoltageLevelStream().map(VoltageLevel::getId).collect(Collectors.toList());
                //Map<String, VlDiffData> finalVlDiffDataMap = vlDiffDataMap;
                Map<String, JsonObject> vlJsonMap = new HashMap<>();
                subVlevelsIds.stream().forEach(vlId -> {
                    JsonObject vlJson = new JsonObject();
                    vlJson.addProperty("id", vlId);
                    if (vlDiffDataMap.containsKey(vlId)) {
                        VlDiffData vlDiffData = vlDiffDataMap.get(vlId);
                        vlJson.addProperty("isDifferent", "true");
                        vlJson.addProperty("minVDelta", vlDiffData.getMinVDelta());
                        vlJson.addProperty("maxVDelta", vlDiffData.getMaxVDelta());
                        isSubDifferent.set(true);
                    } else {
                        vlJson.addProperty("isDifferent", "false");
                    }
                    vlJsonMap.put(vlId, vlJson);
                });

                Feature featureSub = Feature.fromGeometry(Point.fromLngLat(subCoords.getLon(), subCoords.getLat()));
                featureSub.addStringProperty("id", subData.getId());
                featureSub.addBooleanProperty("isDifferent", isSubDifferent.get());
                featureSub.addProperty("vlevels", vlJsonMap.values().stream().collect(JsonArray::new, JsonArray::add, (ja1, ja2) -> ja2.add(ja2)));
                features.add(featureSub);
            } else {
                LOGGER.warn("geo data for substation {} not found", subId);
            }
        }
        FeatureCollection featureCollection = FeatureCollection.fromFeatures(features);
        String subsgeoJson = featureCollection.toJson();
        return subsgeoJson;
    }

    private String extractJsonLines(List<String> subsIds, Map<String, LineGeoData> networkLinesCoordsData, Map<String, DiffData> subsDiffs, List<String> zoneLines) {
        Map<String, LineDiffData> zoneBranchesMap = new HashMap<>();
        for (String subId : subsIds) {
            DiffData diffData = subsDiffs.get(subId);
            List<LineDiffData> branchesDiff = diffData.getLinesDiffData();
            for (LineDiffData lineDiffData : branchesDiff) {
                if (!zoneBranchesMap.containsKey(lineDiffData.getLineId())) {
                    zoneBranchesMap.put(lineDiffData.getLineId(), lineDiffData);
                }
            }
        }

        List<LineGeoData> zoneLinesCoordsData = zoneLines.stream().filter(networkLinesCoordsData::containsKey)
                .map(networkLinesCoordsData::get).collect(Collectors.toList());
        List<Feature> features = new ArrayList<>();
        for (LineGeoData lineData : zoneLinesCoordsData) {
            List<Coordinate> lineCoordinates = lineData.getCoordinates();
            List lineCoord2 = lineCoordinates.stream().map(lc -> Point.fromLngLat(lc.getLon(), lc.getLat())).collect(Collectors.toList());
            Feature featureLine = Feature.fromGeometry(LineString.fromLngLats(lineCoord2));
            featureLine.addStringProperty("id", lineData.getId());
            JsonObject style = new JsonObject();
            style.addProperty("weight", 4);
            if (zoneBranchesMap.containsKey(lineData.getId())) {
                LineDiffData lineDiffData = zoneBranchesMap.get(lineData.getId());
                style.addProperty("color", "#FF0000");
                featureLine.addStringProperty("isDifferent", "true");
                featureLine.addStringProperty("t1_dp", lineDiffData.getpDelta1());
                featureLine.addStringProperty("t1_dq", lineDiffData.getqDelta1());
                featureLine.addStringProperty("t1_di", lineDiffData.getiDelta1());
                featureLine.addStringProperty("t2_dp", lineDiffData.getpDelta2());
                featureLine.addStringProperty("t2_dq", lineDiffData.getqDelta2());
                featureLine.addStringProperty("t2_di", lineDiffData.getiDelta2());
            } else {
                style.addProperty("color", "#0000FF");
                featureLine.addStringProperty("isDifferent", "false");
            }
            style.addProperty("opacity", 1);
            style.addProperty("fillColor", "#FF0000");
            style.addProperty("fillOpacity", 1);
            featureLine.addProperty("style", style);
            features.add(featureLine);
        }
        FeatureCollection featureCollection = FeatureCollection.fromFeatures(features);
        return featureCollection.toJson();
    }
}

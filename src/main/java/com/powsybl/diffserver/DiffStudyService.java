/**
 * Copyright (c) 2020-2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
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
import org.apache.commons.lang3.math.NumberUtils;
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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
    public static final String SAME_LEVEL_COLOR = "black";
    private static final int VARIANTNUM = 0;

    public enum LevelDataType {
        POWER_CURRENT,
        VOLTAGE,
    }

    private WebClient webClient;

    String caseServerBaseUri;
    String networkConversionServerBaseUri;
    String networkStoreServerBaseUri;
    String networkDiffServerBaseUri;
    String geoServerBaseUri;

    private final DiffStudyRepository diffStudyRepository;

    private final String emptyGeoJson = FeatureCollection.fromFeatures(Collections.emptyList()).toJson();

    final Cache<UUID, Map<String, SubstationGeoData>> subsGeoCache;

    final Cache<UUID, Map<String, LineGeoData>> linesGeoCache;

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
            WebClient.Builder webClientBuilder,
            @Value("${network-diff-study-server-config.geoCacheSize:5}") int geoCacheSize) {
        this.caseServerBaseUri = caseServerBaseUri;
        this.networkConversionServerBaseUri = networkConversionServerBaseUri;
        this.networkStoreServerBaseUri = networkStoreServerBaseUri;
        this.networkDiffServerBaseUri = networkDiffServerBaseUri;
        this.geoServerBaseUri = geoServerBaseUri;

        this.webClient = webClientBuilder.build();

        this.diffStudyRepository = studyRepository;

        LOGGER.info("Cache size for geo data: {}", geoCacheSize);
        this.subsGeoCache = CacheBuilder.newBuilder()
                .maximumSize(geoCacheSize)
                .removalListener(new RemovalListener<UUID, Map<String, SubstationGeoData>>() {
                    @Override
                    public void onRemoval(RemovalNotification<UUID, Map<String, SubstationGeoData>> removalNotification) {
                        removalNotification.getValue().keySet().size();
                        LOGGER.info("substations geo data cache, removing entry: {}, cause: {}", removalNotification.getKey(), removalNotification.getCause());
                    }
                })
                .build();

        this.linesGeoCache = CacheBuilder.newBuilder()
                .maximumSize(geoCacheSize)
                .removalListener(new RemovalListener<UUID, Map<String, LineGeoData>>() {
                    @Override
                    public void onRemoval(RemovalNotification<UUID, Map<String, LineGeoData>> removalNotification) {
                        LOGGER.info("lines geo data cache, removing entry for: {}, cause: {}", removalNotification.getKey(), removalNotification.getCause());
                    }
                })
                .build();
    }

    Flux<DiffStudyInfos> getDiffStudyList() {
        Flux<DiffStudy> diffStudyFlux = diffStudyRepository.findAll();
        return diffStudyFlux.sort(Comparator.comparing(DiffStudy::getDiffStudyName)).map(diffStudy ->
                new DiffStudyInfos(diffStudy.getDiffStudyName(), diffStudy.getDescription(), diffStudy.getNetwork1Id(), diffStudy.getCase1Format(), diffStudy.getNetwork2Id(), diffStudy.getCase2Format(), diffStudy.getZone().toArray(String[]::new))
        );
    }

    @Transactional
    public Mono<DiffStudy> createDiffStudy(String diffStudyName, UUID case1Uuid, UUID case2Uuid, String description) {
        Objects.requireNonNull(diffStudyName);
        Objects.requireNonNull(case1Uuid);
        Objects.requireNonNull(case2Uuid);
        Objects.requireNonNull(description);
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

    public static String formatNum(String aNum) {
        return formatNum(aNum, 3, "");
    }

    public static String formatPerc(String aNum) {
        return formatNum(aNum, 2, "");
    }

    public static String formatNum(String aNum, int prec, String suffix) {
        return (NumberUtils.isCreatable(aNum)) ? new BigDecimal(aNum).setScale(prec, RoundingMode.HALF_UP).toString() + suffix : aNum;
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
        Objects.requireNonNull(diffStudyName);
        Objects.requireNonNull(case1File);
        Objects.requireNonNull(case2File);
        Objects.requireNonNull(description);
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
        Objects.requireNonNull(diffStudyName);
        Mono<DiffStudy> studyMono = diffStudyRepository.findByDiffStudyName(diffStudyName);
        return studyMono.switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .flatMap(study ->
                        Mono.whenDelayError(
                                Mono.fromRunnable(() -> removeGeoDataFromCache(study)),
                                deleteNetwork(study.getNetwork1Uuid()),
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

    // This functions call directly the network store server without using the dedicated client because it's a blocking client.
    Mono<List<VoltageLevelAttributes>> getNetworkVoltageLevels(UUID networkUuid) {
        String path = UriComponentsBuilder.fromPath("v1/networks/{networkId}/{variantNum}/voltage-levels")
                .buildAndExpand(networkUuid, VARIANTNUM)
                .toUriString();

        Mono<TopLevelDocument<com.powsybl.network.store.model.VoltageLevelAttributes>> mono = webClient.get()
                .uri(networkStoreServerBaseUri + path)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<TopLevelDocument<com.powsybl.network.store.model.VoltageLevelAttributes>>() {
                });

        return mono.map(t -> t.getData().stream().map(e -> new VoltageLevelAttributes(e.getId(), e.getAttributes().getName(), e.getAttributes().getSubstationId())).collect(Collectors.toList()));
    }

    Mono<List<String>> getNetworkSubstationsIds(UUID networkUuid) {
        String path = UriComponentsBuilder.fromPath("v1/networks/{networkId}/{variantNum}/substations")
                .buildAndExpand(networkUuid, VARIANTNUM)
                .toUriString();

        Mono<TopLevelDocument<com.powsybl.network.store.model.SubstationAttributes>> mono = webClient.get()
                .uri(networkStoreServerBaseUri + path)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<TopLevelDocument<com.powsybl.network.store.model.SubstationAttributes>>() {
                });

        return mono.map(t -> t.getData().stream().map(e -> e.getId()).distinct().collect(Collectors.toList()));
    }

    public Mono<List<VoltageLevelAttributes>> getDiffStudyVoltageLevels(String diffStudyName) {
        Objects.requireNonNull(diffStudyName);
        DiffStudy diffStudy = getDiffStudy(diffStudyName)
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .block();
        return getDiffStudyVoltageLevels(diffStudy);
    }

    private Mono<List<VoltageLevelAttributes>> getDiffStudyVoltageLevels(DiffStudy diffStudy) {

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

    private void removeGeoDataFromCache(DiffStudy study) {
        linesGeoCache.invalidate(study.getCase1Uuid());
        subsGeoCache.invalidate(study.getCase1Uuid());
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

    public Mono<String> getDiffVoltageLevel(String diffStudyName, String voltageLevelId) {
        Objects.requireNonNull(diffStudyName);
        Objects.requireNonNull(voltageLevelId);

        DiffStudy diffStudy = getDiffStudy(diffStudyName)
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .block();
        return getDiffVoltageLevel(diffStudy, voltageLevelId);
    }

    private Mono<String> getDiffVoltageLevel(DiffStudy diffStudy, String voltageLevelId) {
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
        Objects.requireNonNull(diffStudyName);
        Objects.requireNonNull(zone);
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
        Objects.requireNonNull(diffStudyName);
        Objects.requireNonNull(description);
        Mono<DiffStudy> diffStudyMono = diffStudyRepository.findByDiffStudyName(diffStudyName);
        return diffStudyMono
                .log()
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .flatMap(s -> {
                    s.setDescription(description);
                    return diffStudyRepository.save(s);
                });
    }

    private Mono<List<String>> getSubstationsIds(String diffStudyName) {
        return getDiffStudy(diffStudyName)
                .flatMap(s -> getDiffStudyVoltageLevels(s))
                .flatMapIterable(Function.identity())
                .map(s -> s.getSubstationId())
                .sort()
                .distinct()
                .collectList();
    }

    public Mono<List<String>> getNoMatchingSubstations(String diffStudyName, List<String> zone) {
        Objects.requireNonNull(diffStudyName);
        Objects.requireNonNull(zone);
        List<String> subsIds = getSubstationsIds(diffStudyName).block();
        return Mono.just(zone.stream().filter(zs -> !subsIds.contains(zs)).collect(Collectors.toList()));
    }

    public Flux<CaseInfos> searchCase(String query) {
        Objects.requireNonNull(query);
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

    private SubstationGeoData[] getSubsCoordinates(UUID networkUuid) {

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

    private List<String> getZoneLines(UUID networkUuid, List<String> zone) {
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

    private Map<String, LineGeoData> getLinesCoordinatesAsMap(DiffStudy study) {
        try {
            return linesGeoCache.get(study.getCase1Uuid(), new Callable<Map<String, LineGeoData>>() {
                @Override
                public Map<String, LineGeoData> call() {
                    return Arrays.stream(getLinesCoordinates(study.getNetwork1Uuid()))
                            .collect(Collectors.toMap(LineGeoData::getId, geoData -> geoData));
                }
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, LineGeoData> getLinesCoordinatesConnectingSubstationsAsMap(Network network, List<String> zoneLines, Map<String, SubstationGeoData> subsCoordsMap) {
        Map<String, LineGeoData> map = new HashMap<>();
        for (String zoneLineId : zoneLines) {
            Substation sub1 = network.getLine(zoneLineId).getTerminal1().getVoltageLevel().getSubstation();
            Substation sub2 = network.getLine(zoneLineId).getTerminal2().getVoltageLevel().getSubstation();
            SubstationGeoData subData1 = subsCoordsMap.get(sub1.getId());
            SubstationGeoData subData2 = subsCoordsMap.get(sub2.getId());
            if (subData1 != null && subData2 != null && !subData1.getId().equals(subData2.getId())) {
                map.put(zoneLineId, new LineGeoData(zoneLineId, sub1.getCountry().orElse(Country.FR), sub1.getCountry().orElse(Country.FR),
                    sub1.getId(), sub2.getId(), List.of(subData1.getCoordinate(), subData2.getCoordinate())));
            }
        }
        return map;
    }

    private String diffVoltageLevels(Network network1, Network network2, List<String> voltageLevels, List<String> branches) {
        return diffVoltageLevels(network1, network2, voltageLevels, branches, DiffConfig.EPSILON_DEFAULT, DiffConfig.EPSILON_DEFAULT);
    }

    private String diffVoltageLevels(Network network1, Network network2, List<String> voltageLevels, List<String> branches, double threshold, double voltageThreshold) {
        DiffEquipment diffEquipment = new DiffEquipment();
        diffEquipment.setVoltageLevels(voltageLevels);
        List<DiffEquipmentType> equipmentTypes = new ArrayList<DiffEquipmentType>();
        equipmentTypes.add(DiffEquipmentType.VOLTAGE_LEVELS);
        if (!branches.isEmpty()) {
            equipmentTypes.add(DiffEquipmentType.BRANCHES);
            diffEquipment.setBranches(branches);
        }
        diffEquipment.setEquipmentTypes(equipmentTypes);
        DiffConfig config = new DiffConfig(threshold, voltageThreshold, DiffConfig.FILTER_DIFF_DEFAULT);
        NetworkDiff ndiff = new NetworkDiff(config);
        NetworkDiffResults diffVl = ndiff.diff(network1, network2, diffEquipment);
        String jsonDiff = NetworkDiff.writeJson(diffVl);
        //NaN is not part of the JSON standard and frontend would fail when parsing it
        //it should be handled at the source, though
        jsonDiff = jsonDiff.replace(": NaN", ": \"Nan\"");
        jsonDiff = jsonDiff.replace(": Infinity,", ": \"Infinity\",");
        jsonDiff = jsonDiff.replace(": -Infinity,", ": \"-Infinity\",");
        return jsonDiff;
    }

    private String diffSubstation(Network network1, Network network2, String substationId) {
        return diffSubstation(network1, network2, substationId, DiffConfig.EPSILON_DEFAULT, DiffConfig.EPSILON_DEFAULT);
    }

    private String diffSubstation(Network network1, Network network2, String substationId, double threshold, double voltageThreshold) {
        Substation substation1 = network1.getSubstation(substationId);
        List<String> voltageLevels = substation1.getVoltageLevelStream().map(VoltageLevel::getId)
                .collect(Collectors.toList());
        List<String> branches = substation1.getVoltageLevelStream().flatMap(vl -> vl.getConnectableStream(Line.class))
                .map(Line::getId).collect(Collectors.toList());
        List<String> twts = substation1.getTwoWindingsTransformerStream().map(TwoWindingsTransformer::getId)
                .collect(Collectors.toList());
        branches.addAll(twts);
        String jsonDiff = diffVoltageLevels(network1, network2, voltageLevels, branches, threshold, voltageThreshold);
        return jsonDiff;
    }

    private Map<String, SubstationGeoData> getSubsCoordinatesAsMap(DiffStudy study) {
        try {
            return subsGeoCache.get(study.getCase1Uuid(), new Callable<Map<String, SubstationGeoData>>() {
                @Override
                public Map<String, SubstationGeoData> call() {
                    return Arrays.stream(getSubsCoordinates(study.getNetwork1Uuid()))
                            .collect(Collectors.toMap(SubstationGeoData::getId, geoData -> geoData));
                }
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private List<SubstationGeoData> getSubsGeoData(DiffStudy diffStudy) {
        List<String> subsIds = diffStudy.getZone();
        if (!subsIds.isEmpty()) {
            Map<String, SubstationGeoData> mapSubGeodata = getSubsCoordinatesAsMap(diffStudy);
            return subsIds.stream().filter(mapSubGeodata::containsKey).map(mapSubGeodata::get).collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    public List<SubstationGeoData> getSubsGeoData(String diffStudyName) {
        Objects.requireNonNull(diffStudyName);
        DiffStudy diffStudy = getDiffStudy(diffStudyName)
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .block();
        return getSubsGeoData(diffStudy);
    }

    private JsonObject wrapJsonWithMeta(String name, String data) {
        JsonObject layerObj = new JsonObject();
        layerObj.addProperty("name", name);
        layerObj.addProperty("data", data);
        return layerObj;
    }

    public String getGeoJsonLayers(String diffStudyName, double threshold, double voltageThreshold, List<String> layersIds, String levels) {
        try {
            return extractGeoJsonLayers(diffStudyName, threshold, voltageThreshold, layersIds, levels);
        } catch (RuntimeException | IOException e) {
            LOGGER.error(e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private String extractGeoJsonLayers(String diffStudyName, double threshold, double voltageThreshold, List<String> layersIds, String levels) throws IOException {
        Objects.requireNonNull(diffStudyName);
        Objects.requireNonNull(layersIds);
        JsonObject retJson = new JsonObject();
        JsonArray jsonArray = new JsonArray();

        if (layersIds.size() == 0) {
            throw new RuntimeException("at least one layer type id is required");
        }

        LevelsData levelsData = LevelsData.parseData(levels);
        LOGGER.info("levels data: {}", levelsData);

        LOGGER.info("study name: {}, threshold: {}, voltageThreshold: {}, layersIds: {}", diffStudyName, threshold, voltageThreshold, layersIds);
        DiffStudy diffStudy = getDiffStudy(diffStudyName)
                .switchIfEmpty(Mono.error(new DiffStudyException(DIFF_STUDY_DOESNT_EXISTS)))
                .block();
        List<String> subsIds = diffStudy.getZone();
        if (!subsIds.isEmpty()) {
            Network network1 = getNetwork(diffStudy.getNetwork1Uuid());
            Network network2 = getNetwork(diffStudy.getNetwork2Uuid());

            // map subId, diffData
            Map<String, DiffData> subsDiffs = new HashMap<>();
            for (String subId : subsIds) {
                String jsonDiff = diffSubstation(network1, network2, subId, threshold, voltageThreshold);
                DiffData diffData = DiffData.parseData(jsonDiff);
                subsDiffs.put(subId, diffData);
            }

            if (layersIds.contains("SUBS")) {
                //subs geoJson
                // map subId, substationGeoData
                Map<String, SubstationGeoData> allSubsGeodata = getSubsCoordinatesAsMap(diffStudy);
                Map<String, SubstationGeoData> zoneSubsGeodata = subsIds.stream().filter(allSubsGeodata::containsKey).map(allSubsGeodata::get).collect(Collectors.toMap(SubstationGeoData::getId, t -> t));
                String subsgeoJson = extractJsonSubs(subsIds, subsDiffs, zoneSubsGeodata, network1, levelsData);
                jsonArray.add(wrapJsonWithMeta("SUBS", subsgeoJson));
            }

            List<String> zoneLines = getZoneLines(diffStudy.getNetwork1Uuid(), diffStudy.getZone());
            if (layersIds.contains("LINES")) {
                // lines geoJson (true coordinates) note: retrieve linesGeoData for all the network lines is quite expensive
                Map<String, LineGeoData> networkLinesCoordsData = getLinesCoordinatesAsMap(diffStudy);
                String linesGeoJson = extractJsonLines(subsIds, networkLinesCoordsData, subsDiffs, zoneLines, levelsData);
                jsonArray.add(wrapJsonWithMeta("LINES", linesGeoJson));
            }

            if (layersIds.contains("LINES-SIMPLE")) {
                //simple lines (connecting substations)
                Map<String, LineGeoData> networkLinesCoordsData2 = getLinesCoordinatesConnectingSubstationsAsMap(network1, zoneLines,
                        getSubsCoordinatesAsMap(diffStudy));
                String simpleLinesGeoJson = extractJsonLines(subsIds, networkLinesCoordsData2, subsDiffs, zoneLines, levelsData);
                jsonArray.add(wrapJsonWithMeta("LINES-SIMPLE", simpleLinesGeoJson));
            }
        } else {
            //return empty layers
            jsonArray.add(wrapJsonWithMeta("SUBS", emptyGeoJson));
            jsonArray.add(wrapJsonWithMeta("LINES", emptyGeoJson));
            jsonArray.add(wrapJsonWithMeta("LINES-SIMPLE", emptyGeoJson));
        }

        retJson.add("layers", jsonArray);
        return retJson.toString();
    }

    private String extractJsonSubs(List<String> subsIds, Map<String, DiffData> subsDiffs, Map<String, SubstationGeoData> zoneSubsGeodata, Network network1, LevelsData levelsData) {
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
                Map<String, JsonObject> vlJsonMap = new HashMap<>();
                subVlevelsIds.stream().forEach(vlId -> {
                    JsonObject vlJson = new JsonObject();
                    vlJson.addProperty("id", vlId);
                    if (vlDiffDataMap.containsKey(vlId)) {
                        VlDiffData vlDiffData = vlDiffDataMap.get(vlId);
                        vlJson.addProperty("isDifferent", "true");
                        vlJson.addProperty("minVDelta", vlDiffData.getMinVDelta());
                        vlJson.addProperty("maxVDelta", vlDiffData.getMaxVDelta());
                        vlJson.addProperty("minVDeltaPerc", vlDiffData.getMinVDeltaPerc());
                        vlJson.addProperty("maxVDeltaPerc", vlDiffData.getMaxVDeltaPerc());
                        vlJson.addProperty("color", assignColorToVl(vlDiffData, levelsData));
                        isSubDifferent.set(true);
                    } else {
                        vlJson.addProperty("isDifferent", "false");
                        vlJson.addProperty("color", SAME_LEVEL_COLOR);
                    }
                    vlJsonMap.put(vlId, vlJson);
                });

                String subColor = assignColorToSub(subVlevelsIds.stream().filter(vlDiffDataMap::containsKey).map(vlDiffDataMap::get).collect(Collectors.toList()), levelsData);
                Feature featureSub = Feature.fromGeometry(Point.fromLngLat(subCoords.getLon(), subCoords.getLat()));
                featureSub.addStringProperty("id", subData.getId());
                featureSub.addBooleanProperty("isDifferent", isSubDifferent.get());
                featureSub.addStringProperty("subColor", subColor);
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

    private String extractJsonLines(List<String> subsIds, Map<String, LineGeoData> networkLinesCoordsData, Map<String, DiffData> subsDiffs, List<String> zoneLines, LevelsData levelsData) {
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
            String lineColor = SAME_LEVEL_COLOR;
            if (zoneBranchesMap.containsKey(lineData.getId())) {
                LineDiffData lineDiffData = zoneBranchesMap.get(lineData.getId());
                lineColor = assignColorToLine(lineDiffData, levelsData);
                featureLine.addStringProperty("isDifferent", "true");
                featureLine.addStringProperty("t1_dp", lineDiffData.getpDelta1());
                featureLine.addStringProperty("t1_dq", lineDiffData.getqDelta1());
                featureLine.addStringProperty("t1_di", lineDiffData.getiDelta1());
                featureLine.addStringProperty("t2_dp", lineDiffData.getpDelta2());
                featureLine.addStringProperty("t2_dq", lineDiffData.getqDelta2());
                featureLine.addStringProperty("t2_di", lineDiffData.getiDelta2());
                featureLine.addStringProperty("t1_dp_perc", lineDiffData.getpDelta1Perc());
                featureLine.addStringProperty("t1_dq_perc", lineDiffData.getqDelta1Perc());
                featureLine.addStringProperty("t1_di_perc", lineDiffData.getiDelta1Perc());
                featureLine.addStringProperty("t2_dp_perc", lineDiffData.getpDelta2Perc());
                featureLine.addStringProperty("t2_dq_perc", lineDiffData.getqDelta2Perc());
                featureLine.addStringProperty("t2_di_perc", lineDiffData.getiDelta2Perc());
            } else {
                featureLine.addStringProperty("isDifferent", "false");
            }
            style.addProperty("color", lineColor);
            style.addProperty("opacity", 1);
            style.addProperty("fillOpacity", 1);
            featureLine.addProperty("style", style);
            features.add(featureLine);
        }
        FeatureCollection featureCollection = FeatureCollection.fromFeatures(features);
        return featureCollection.toJson();
    }

    private String assignColorFromDoubleList(List<Double> values, LevelsData levelsData, LevelDataType lDataType) {
        if ((values == null) || (levelsData == null)) {
            return SAME_LEVEL_COLOR;
        }
        double maxValue = values.stream().max(Comparator.naturalOrder()).orElse(0.0);
        List<LevelData> orderedLevels = levelsData.getLevels().stream().sorted(Comparator
                .comparing((lDataType == LevelDataType.POWER_CURRENT) ? LevelData::getI : LevelData::getV).reversed()).collect(Collectors.toList());
        for (int i = 0; i < orderedLevels.size(); i++) {
            LevelData ld = orderedLevels.get(i);
            double compValue = (lDataType == LevelDataType.POWER_CURRENT) ? ld.getI() : ld.getV();
            if (maxValue >= compValue) {
                return ld.getC();
            }
        }
        return SAME_LEVEL_COLOR;
    }

    private String assignColorFromStringList(List<String> values, LevelsData levelsData, LevelDataType lDataType) {
        List<Double> diffPercDoubleList = values.stream().filter(NumberUtils::isCreatable).map(Double::valueOf).map(Math::abs).collect(Collectors.toList());
        return assignColorFromDoubleList(diffPercDoubleList, levelsData, lDataType);
    }

    private String assignColorToLine(LineDiffData lineDiffData, LevelsData levelsData) {
        List<String> diffPercList = List.of(lineDiffData.iDelta1Perc, lineDiffData.iDelta2Perc, lineDiffData.pDelta1Perc,
                lineDiffData.pDelta2Perc, lineDiffData.qDelta1Perc, lineDiffData.qDelta2Perc);
        return assignColorFromStringList(diffPercList, levelsData, LevelDataType.POWER_CURRENT);
    }

    private String assignColorToVl(VlDiffData vlDiffData, LevelsData levelsData) {
        List<String> diffPercList = List.of(vlDiffData.getMinVDeltaPerc(), vlDiffData.getMaxVDeltaPerc());
        return assignColorFromStringList(diffPercList, levelsData, LevelDataType.VOLTAGE);
    }

    private String assignColorToSub(List<VlDiffData> vlList, LevelsData levelsData) {
        List<String> vlPercentages = vlList.stream().map(vlData -> List.of(vlData.getMinVDeltaPerc(), vlData.getMaxVDeltaPerc()))
                .flatMap(List::stream).collect(Collectors.toList());
        return assignColorFromStringList(vlPercentages, levelsData, LevelDataType.VOLTAGE);
    }
}

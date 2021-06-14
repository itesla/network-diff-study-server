/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LevelsData {

    List<LevelData> levels;

    public static LevelsData parseData(String jsonData) {
        return parseData(jsonData, true);
    }

    public static LevelsData parseData(String jsonData, boolean urlDecode) {
        if (jsonData == null) {
            return null;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            LevelsData levelsData = objectMapper.readValue(urlDecode ? URLDecoder.decode(jsonData, StandardCharsets.UTF_8.toString()) : jsonData, LevelsData.class);
            return levelsData;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "LevelsData{" +
                "levels=" + levels +
                '}';
    }
}

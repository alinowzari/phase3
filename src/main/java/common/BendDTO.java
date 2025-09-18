// common/BendDTO.java
package common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BendDTO(PointDTO start, PointDTO middle, PointDTO end) {}

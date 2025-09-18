// AbilityType.java (and repeat the same annotation on all your enums below)
package common;

import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum AbilityType {
    WRATH_OF_PENIA, WRATH_OF_AERGIA, SPEED_BOOST, BRING_BACK_TO_CENTER, ZERO_ACCEL
}

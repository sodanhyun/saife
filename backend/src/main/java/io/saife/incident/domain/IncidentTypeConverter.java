package io.saife.incident.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code incident.accident_type} 열 값과 {@link IncidentType}. 모르는 값은 null로 읽는다 */
@Converter
public class IncidentTypeConverter implements AttributeConverter<IncidentType, String> {

    @Override
    public String convertToDatabaseColumn(IncidentType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public IncidentType convertToEntityAttribute(String dbData) {
        return IncidentType.parse(dbData);
    }
}

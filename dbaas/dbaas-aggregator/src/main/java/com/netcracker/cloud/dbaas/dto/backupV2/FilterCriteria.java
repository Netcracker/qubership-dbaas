package com.netcracker.cloud.dbaas.dto.backupV2;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "Group of filters for backup and restore operations. Filters are applied in the following order:\n" +
        "\n" +
        "1. `filter`: Apply the filter to the databases.\n" +
        "2. `include`: Include databases that match any of the filters in the list.\n" +
        "3. `exclude`: Exclude databases that match any of the filters in the list.")
public class FilterCriteria {
    @Schema(
            description = "Apply the filter to the remaining databases",
            required = true
    )
    @NotNull
    @Size(min = 1)
    private List<Filter> filter;
    @Schema(
            description = "Include databases that match any of the filters in the list"
    )
    private List<Filter> include;
    @Schema(
            description = "Exclude databases that match any of the filters in the list"
    )
    private List<Filter> exclude;
}

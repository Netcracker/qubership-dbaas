package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.utils.validation.group.BackupGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "Group of filters for backup and restore operations. Filters are applied in the following order:\n" +
        "\n" +
        "1. `filter`\n" +
        "2. `exclude`")
public class FilterCriteria {
    @Schema(
            description = "Include databases that match any of the filters in the list"
    )
    @NotNull(groups = {BackupGroup.class})
    @Size(min = 1, groups = {BackupGroup.class}, message = "there should be at least one filter specified")
    @Valid
    private List<Filter> filter = new ArrayList<>();

    @Schema(
            description = "Exclude databases that match any of the filters in the list"
    )
    @Valid
    private List<Filter> exclude = new ArrayList<>();
}

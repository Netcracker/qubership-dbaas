package org.qubership.cloud.dbaas.entity.pg;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Table(name = "bg_track")
@Entity(name = "BgTrack")
public class BgTrack {
    @Id
    private String id;
    private String namespace;
    private String operation;

}

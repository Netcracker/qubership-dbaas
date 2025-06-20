package org.qubership.cloud.dbaas.entity.pg;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bg_domain")
@Data
@JsonIdentityInfo(
        generator = ObjectIdGenerators.PropertyGenerator.class,
        property = "id")
public class BgDomain implements Serializable {

    @Id
    @GeneratedValue
    private UUID id;

    @Schema(description = "blue green namespaces in the same domain")
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "bgDomain")
    @ToString.Exclude
    private List<BgNamespace> namespaces;

    @Schema(description = "blue green controller namespaces")
    @Column(name = "controller_namespace")
    private String controllerNamespace;

    @Schema(description = "blue green origin namespaces")
    @Column(name = "origin_namespace")
    private String originNamespace;

    @Schema(description = "blue green peer namespaces")
    @Column(name = "peer_namespace")
    private String peerNamespace;

}

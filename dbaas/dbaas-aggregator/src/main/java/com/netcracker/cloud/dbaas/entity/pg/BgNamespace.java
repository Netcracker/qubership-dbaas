package org.qubership.cloud.dbaas.entity.pg;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import org.qubership.cloud.dbaas.dto.bluegreen.BgStateRequest;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;

@Data
@NoArgsConstructor
@Entity
@Table(name = "bg_namespace")
@JsonIdentityInfo(
        generator = ObjectIdGenerators.PropertyGenerator.class,
        property = "namespace")
public class BgNamespace implements Serializable {

    @Id
    private String namespace;
    private String state;
    private String version;
    @Column(name = "update_time")
    private Date updateTime;

    @ManyToOne
    @JoinColumn(name = "bg_domain_id")
    @ToString.Exclude
    private BgDomain bgDomain;

    public BgNamespace(BgStateRequest.BGStateNamespace namespace, Date updateTime) {
        this.updateTime = updateTime;
        this.namespace = namespace.getName();
        this.version = namespace.getVersion();
        this.state = namespace.getState();
    }
}

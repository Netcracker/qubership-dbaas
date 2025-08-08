package org.qubership.cloud.dbaas.entity.h2;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@Entity(name = "DbaasUser")
@Table(name = "users")
public class DbaasUser {

    @Id
    @GeneratedValue
    private UUID id;

    private String username;
    private String password;

    @ElementCollection
    @CollectionTable(name = "roles", joinColumns = @JoinColumn(name="user_id"))
    @Column(name="role")
    private List<String> roles;

}

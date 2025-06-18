package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import org.qubership.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DumpRulesV3 {

    private List<DumpDefaultRuleV3> defaultRules;

    private List<PerNamespaceRule> namespaceRules;

    private List<PerMicroserviceRule> microserviceRules;

    private List<PerNamespaceRule> permanentRules;
}

package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.backup.v1.Filter;
import com.netcracker.it.dbaas.entity.backup.v1.FilterCriteria;

import java.util.List;
import java.util.function.Consumer;

public class FilterCriteriaBuilder {
    private final FilterCriteria filterCriteria;

    public FilterCriteriaBuilder() {
        this.filterCriteria = new FilterCriteria();
    }

    public FilterCriteriaBuilder(FilterCriteria filterCriteria) {
        this.filterCriteria = filterCriteria;
    }

    public FilterCriteriaBuilder include(Filter filter) {
        this.filterCriteria.getInclude().add(filter);
        return this;
    }

    public FilterCriteriaBuilder exclude(Filter exclude) {
        this.filterCriteria.getExclude().add(exclude);
        return this;
    }

    public FilterCriteriaBuilder include(List<Filter> filters) {
        this.filterCriteria.setInclude(filters);
        return this;
    }

    public FilterCriteriaBuilder exclude(List<Filter> excludes) {
        this.filterCriteria.setExclude(excludes);
        return this;
    }

    public FilterCriteriaBuilder include(Consumer<FilterBuilder> fbc) {
        var include = new FilterBuilder();
        fbc.accept(include);
        this.filterCriteria.getInclude().add(include.build());
        return this;
    }

    public FilterCriteriaBuilder exclude(Consumer<FilterBuilder> ebc) {
        var exclude = new FilterBuilder();
        ebc.accept(exclude);
        this.filterCriteria.getExclude().add(exclude.build());
        return this;
    }

    public FilterCriteria build() {
        return this.filterCriteria;
    }
}

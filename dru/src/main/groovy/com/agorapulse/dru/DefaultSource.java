package com.agorapulse.dru;

import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import java.io.InputStream;

class DefaultSource implements SourceDefinition, Source {

    DefaultSource(Object referenceObject, String path) {
        this.referenceObject = referenceObject;
        this.path = path;
        propertyMappings = new PropertyMappings(path);
    }

    @Override
    public SourceDefinition map(String path,
                                @DelegatesTo(value = com.agorapulse.dru.PropertyMappingDefinition.class, strategy = Closure.DELEGATE_FIRST)
                Closure<PropertyMappingDefinition> configuration
    ) {
        PropertyMapping mapping = propertyMappings.findOrCreate(path);
        DefaultGroovyMethods.with(mapping, configuration);
        return this;
    }

    @Override
    public SourceDefinition map(Closure<PropertyMappingDefinition> configuration) {
        return map("", configuration);
    }

    @Override
    public String getPath() {
        return path;
    }

    public Object getReferenceObject() {
        return referenceObject;
    }

    @Override
    public InputStream getSourceStream() {
        Class reference = referenceObject instanceof Class ? (Class) referenceObject : referenceObject.getClass();
        String path = reference.getSimpleName() + "/" + this.path;
        InputStream stream = reference.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Source '" + path + "' not found relative to " + reference);
        }
        return stream;
    }

    @Override
    public PropertyMappings getRootPropertyMappings() {
        return propertyMappings;
    }

    @Override
    public String toString() {
        return "Source[" + path + "] relative to " + referenceObject;
    }

    private final Object referenceObject;
    private final String path;
    private final PropertyMappings propertyMappings;
}

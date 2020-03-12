package io.scicast.streamesh.core.internal.reflect.handler;

import io.scicast.streamesh.core.StreameshContext;
import io.scicast.streamesh.core.internal.reflect.GrammarMarkerHandler;
import io.scicast.streamesh.core.internal.reflect.LocallyScoped;
import io.scicast.streamesh.core.internal.reflect.Scope;

public class LocallyScopedHandler implements GrammarMarkerHandler<LocallyScoped> {

    @Override
    public Scope handle(Scope scope, StreameshContext context, Object instance, LocallyScoped annotation) {
        return null;
    }
}

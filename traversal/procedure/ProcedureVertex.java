/*
 * Copyright (C) 2021 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.traversal.procedure;

import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Label;
import grakn.core.graph.GraphManager;
import grakn.core.graph.common.Encoding;
import grakn.core.graph.edge.ThingEdge;
import grakn.core.graph.vertex.AttributeVertex;
import grakn.core.graph.vertex.ThingVertex;
import grakn.core.graph.vertex.TypeVertex;
import grakn.core.graph.vertex.Vertex;
import grakn.core.traversal.Traversal;
import grakn.core.traversal.common.Identifier;
import grakn.core.traversal.graph.TraversalVertex;
import grakn.core.traversal.predicate.Predicate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static grakn.common.collection.Collections.set;
import static grakn.common.util.Objects.className;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_CAST;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static grakn.core.common.exception.ErrorMessage.TypeRead.TYPE_NOT_ATTRIBUTE_TYPE;
import static grakn.core.common.exception.ErrorMessage.TypeRead.TYPE_NOT_FOUND;
import static grakn.core.common.iterator.Iterators.iterate;
import static grakn.core.common.iterator.Iterators.link;
import static grakn.core.common.iterator.Iterators.single;
import static grakn.core.common.iterator.Iterators.tree;
import static grakn.core.graph.common.Encoding.Edge.Type.SUB;
import static grakn.core.graph.common.Encoding.ValueType.STRING;
import static grakn.core.graph.common.Encoding.Vertex.Thing.ROLE;
import static grakn.core.traversal.predicate.PredicateOperator.Equality.EQ;

public abstract class ProcedureVertex<
        VERTEX extends Vertex<?, ?>,
        PROPERTIES extends TraversalVertex.Properties
        > extends TraversalVertex<ProcedureEdge<?, ?>, PROPERTIES> {

    private final boolean isStartingVertex;
    private final AtomicReference<Set<Integer>> dependedEdgeOrders;
    private ProcedureEdge<?, ?> iteratorEdge;

    ProcedureVertex(Identifier identifier, boolean isStartingVertex) {
        super(identifier);
        this.isStartingVertex = isStartingVertex;
        this.dependedEdgeOrders = new AtomicReference<>(null);
    }

    public abstract ResourceIterator<? extends VERTEX> iterator(GraphManager graphMgr, Traversal.Parameters parameters);

    @Override
    public void in(ProcedureEdge<?, ?> edge) {
        super.in(edge);
        if (iteratorEdge == null || edge.order() < iteratorEdge.order()) iteratorEdge = edge;
    }

    public boolean isStartingVertex() {
        return isStartingVertex;
    }

    public Set<Integer> dependedEdgeOrders() {
        dependedEdgeOrders.compareAndSet(null, computeDependedEdgeOrders());
        return dependedEdgeOrders.get();
    }

    private Set<Integer> computeDependedEdgeOrders() {
        if (ins().isEmpty()) return set();
        else return set(branchEdge().from().dependedEdgeOrders(), branchEdge().order());
    }

    public ProcedureEdge<?, ?> branchEdge() {
        if (ins().isEmpty()) return null;
        else return iteratorEdge;
    }

    public ProcedureVertex.Thing asThing() {
        throw GraknException.of(ILLEGAL_CAST, className(this.getClass()), className(ProcedureVertex.Thing.class));
    }

    public ProcedureVertex.Type asType() {
        throw GraknException.of(ILLEGAL_CAST, className(this.getClass()), className(ProcedureVertex.Type.class));
    }

    static TypeVertex assertTypeNotNull(TypeVertex type, Label label) {
        // TODO: replace this with assertions once query validation is implemented
        // TODO: what happens to the state of transaction if we throw in a traversal/match?
        if (type == null) throw GraknException.of(TYPE_NOT_FOUND, label);
        else return type;
    }

    @Override
    public String toString() {
        String str = super.toString();
        if (isStartingVertex) str += " (start)";
        if (outs().isEmpty()) str += " (end)";
        return str;
    }

    public static class Thing extends ProcedureVertex<ThingVertex, Properties.Thing> {

        Thing(Identifier identifier, boolean isStartingVertex) {
            super(identifier, isStartingVertex);
        }

        @Override
        protected Properties.Thing newProperties() {
            return new Properties.Thing();
        }

        @Override
        public boolean isThing() { return true; }

        @Override
        public ProcedureVertex.Thing asThing() { return this; }

        @Override
        public ResourceIterator<? extends ThingVertex> iterator(GraphManager graphMgr, Traversal.Parameters parameters) {
            assert isStartingVertex();
            if (props().hasIID()) return iterateAndFilterFromIID(graphMgr, parameters);
            else if (!props().types().isEmpty()) return iterateAndFilterFromTypes(graphMgr, parameters);
            else if (mustBeAttribute()) return iterateAndFilterFromAttributes(graphMgr, parameters);
            else if (mustBeRelation()) return iterateFromAll(graphMgr, graphMgr.schema().rootRelationType());
            else if (mustBeRole()) return iterateFromAll(graphMgr, graphMgr.schema().rootRoleType());
            else if (mustBeThing()) return iterateFromAll(graphMgr, graphMgr.schema().rootThingType());
            else throw GraknException.of(ILLEGAL_STATE);
        }

        ResourceIterator<? extends ThingVertex> filter(ResourceIterator<? extends ThingVertex> iterator,
                                                       Traversal.Parameters params) {
            if (props().hasIID()) iterator = filterIID(iterator, params);
            if (!props().types().isEmpty()) iterator = filterTypes(iterator);
            if (!props().predicates().isEmpty()) iterator = filterPredicates(filterAttributes(iterator), params);
            return iterator;
        }

        private boolean mustBeAttribute() {
            return !props().predicates().isEmpty() || iterate(outs()).anyMatch(ProcedureEdge::onlyStartsFromAttribute);
        }

        private boolean mustBeRelation() {
            return iterate(outs()).anyMatch(ProcedureEdge::onlyStartsFromRelation);
        }

        private boolean mustBeRole() {
            return id().isScoped();
        }

        private boolean mustBeThing() {
            return id().isVariable();
        }

        private ResourceIterator<? extends AttributeVertex<?>> iterateAndFilterFromAttributes(
                GraphManager graph, Traversal.Parameters parameters) {
            ResourceIterator<? extends AttributeVertex<?>> iter;
            ResourceIterator<TypeVertex> attTypes;

            Optional<Predicate.Value<?>> eq = iterate(props().predicates()).filter(p -> p.operator().equals(EQ)).first();

            if (eq.isPresent()) {
                attTypes = iterate(eq.get().valueType().assignables())
                        .flatMap(vt -> graph.schema().attributeTypes(vt));
                iter = iteratorOfAttributes(graph, attTypes, parameters, eq.get());
            } else {
                if (!props().predicates().isEmpty()) {
                    attTypes = iterate(props().predicates())
                            .flatMap(p -> iterate(p.valueType().comparables()))
                            .flatMap(vt -> graph.schema().attributeTypes(vt));
                } else {
                    attTypes = tree(graph.schema().rootAttributeType(), a -> a.ins().edge(SUB).from());
                }
                iter = attTypes.flatMap(t -> graph.data().get(t)).map(ThingVertex::asAttribute);
            }

            if (props().predicates().isEmpty()) return iter;
            else return filterPredicates(iter, parameters, eq.orElse(null));
        }

        private ResourceIterator<ThingVertex> iterateFromAll(GraphManager graphMgr, TypeVertex rootType) {
            return tree(rootType, t -> t.ins().edge(SUB).from()).flatMap(t -> graphMgr.data().get(t));
        }

        ResourceIterator<? extends ThingVertex> iterateAndFilterFromIID(GraphManager graphMgr, Traversal.Parameters parameters) {
            assert props().hasIID() && id().isVariable();
            Identifier.Variable id = id().asVariable();
            ResourceIterator<? extends ThingVertex> iter = single(graphMgr.data().get(parameters.getIID(id))).noNulls();
            if (!props().types().isEmpty()) iter = filterTypes(iter);
            if (!props().predicates().isEmpty()) iter = filterPredicates(filterAttributes(iter), parameters);
            return iter;
        }

        ResourceIterator<? extends ThingVertex> iterateAndFilterFromTypes(GraphManager graphMgr,
                                                                          Traversal.Parameters parameters) {
            assert !props().types().isEmpty();
            ResourceIterator<? extends ThingVertex> iter;
            Optional<Predicate.Value<?>> eq = iterate(props().predicates()).filter(p -> p.operator().equals(EQ)).first();
            if (eq.isPresent()) iter = iteratorOfAttributesWithTypes(graphMgr, parameters, eq.get());
            else iter = iterate(props().types().iterator())
                    .map(l -> assertTypeNotNull(graphMgr.schema().getType(l), l))
                    .flatMap(t -> graphMgr.data().get(t));

            if (id().isVariable()) iter = filterReferableThings(iter);
            if (props().predicates().isEmpty()) return iter;
            else return filterPredicates(filterAttributes(iter), parameters, eq.orElse(null));
        }

        ResourceIterator<? extends ThingVertex> filterReferableThings(ResourceIterator<? extends ThingVertex> iterator) {
            assert id().isVariable();
            return iterator.filter(v -> !v.encoding().equals(ROLE));
        }

        ResourceIterator<? extends ThingVertex> filterIID(ResourceIterator<? extends ThingVertex> iterator,
                                                          Traversal.Parameters parameters) {
            return iterator.filter(v -> v.iid().equals(parameters.getIID(id().asVariable())));
        }

        ResourceIterator<ThingEdge> filterIIDOnEdge(ResourceIterator<ThingEdge> iterator,
                                                    Traversal.Parameters parameters, boolean isForward) {
            Function<ThingEdge, ThingVertex> fn = e -> isForward ? e.to() : e.from();
            return iterator.filter(e -> fn.apply(e).iid().equals(parameters.getIID(id().asVariable())));
        }

        ResourceIterator<? extends ThingVertex> filterTypes(ResourceIterator<? extends ThingVertex> iterator) {
            return iterator.filter(v -> props().types().contains(v.type().properLabel()));
        }

        ResourceIterator<ThingEdge> filterTypesOnEdge(ResourceIterator<ThingEdge> iterator, boolean isForward) {
            Function<ThingEdge, ThingVertex> fn = e -> isForward ? e.to() : e.from();
            return iterator.filter(e -> props().types().contains(fn.apply(e).type().properLabel()));
        }

        ResourceIterator<? extends AttributeVertex<?>> filterPredicates(ResourceIterator<? extends AttributeVertex<?>> iterator,
                                                                        Traversal.Parameters parameters) {
            return filterPredicates(iterator, parameters, null);
        }

        ResourceIterator<? extends AttributeVertex<?>> filterPredicates(ResourceIterator<? extends AttributeVertex<?>> iterator,
                                                                        Traversal.Parameters parameters,
                                                                        @Nullable Predicate.Value<?> exclude) {
            // TODO: should we throw an exception if the user asserts a value predicate on a non-attribute?
            // TODO: should we throw an exception if the user assert a value non-comparable value types?
            assert id().isVariable();
            for (Predicate.Value<?> predicate : props().predicates()) {
                if (Objects.equals(predicate, exclude)) continue;
                for (Traversal.Parameters.Value value : parameters.getValues(id().asVariable(), predicate)) {
                    iterator = iterator.filter(a -> predicate.apply(a, value));
                }
            }
            return iterator;
        }

        ResourceIterator<ThingEdge> filterPredicatesOnEdge(ResourceIterator<ThingEdge> iterator,
                                                           Traversal.Parameters parameters, boolean isForward) {
            assert id().isVariable();
            Function<ThingEdge, ThingVertex> fn = e -> isForward ? e.to() : e.from();
            iterator = iterator.filter(e -> fn.apply(e).isAttribute());
            for (Predicate.Value<?> predicate : props().predicates()) {
                for (Traversal.Parameters.Value value : parameters.getValues(id().asVariable(), predicate)) {
                    iterator = iterator.filter(e -> predicate.apply(fn.apply(e).asAttribute(), value));
                }
            }
            return iterator;
        }

        ResourceIterator<? extends AttributeVertex<?>> iteratorOfAttributesWithTypes(
                GraphManager graphMgr, Traversal.Parameters params, Predicate.Value<?> eq) {
            ResourceIterator<TypeVertex> attributeTypes = iterate(props().types().iterator())
                    .map(l -> graphMgr.schema().getType(l)).noNulls()
                    .map(t -> {
                        if (t.isAttributeType()) return t;
                        else throw GraknException.of(TYPE_NOT_ATTRIBUTE_TYPE, t.properLabel());
                    }).filter(t -> eq.valueType().assignables().contains(t.valueType()));
            return iteratorOfAttributes(graphMgr, attributeTypes, params, eq);
        }

        ResourceIterator<? extends AttributeVertex<?>> iteratorOfAttributes(
                GraphManager graphMgr, ResourceIterator<TypeVertex> attributeTypes,
                Traversal.Parameters parameters, Predicate.Value<?> eqPredicate) {
            // TODO: should we throw an exception if the user asserts 2 values for a given vertex?
            assert id().isVariable();
            Set<Traversal.Parameters.Value> values = parameters.getValues(id().asVariable(), eqPredicate);
            assert values.size() == 1;
            return attributeTypes.map(t -> attributeVertex(graphMgr, t, values.iterator().next())).noNulls();
        }

        private AttributeVertex<?> attributeVertex(GraphManager graphMgr, TypeVertex type,
                                                   Traversal.Parameters.Value value) {
            assert type.isAttributeType();
            switch (type.valueType()) {
                case BOOLEAN:
                    return graphMgr.data().get(type, value.getBoolean());
                case LONG:
                    return graphMgr.data().get(type, value.getLong());
                case DOUBLE:
                    return graphMgr.data().get(type, value.getDouble());
                case STRING:
                    return graphMgr.data().get(type, value.getString());
                case DATETIME:
                    return graphMgr.data().get(type, value.getDateTime());
                default:
                    throw GraknException.of(ILLEGAL_STATE);
            }
        }

        static ResourceIterator<? extends AttributeVertex<?>> filterAttributes(ResourceIterator<? extends ThingVertex> iterator) {
            return iterator.filter(ThingVertex::isAttribute).map(ThingVertex::asAttribute);
        }
    }

    public static class Type extends ProcedureVertex<TypeVertex, Properties.Type> {

        Type(Identifier identifier, boolean isStartingVertex) {
            super(identifier, isStartingVertex);
        }

        @Override
        protected Properties.Type newProperties() {
            return new Properties.Type();
        }

        @Override
        public ResourceIterator<TypeVertex> iterator(GraphManager graphMgr, Traversal.Parameters parameters) {
            assert isStartingVertex() && id().isVariable();
            ResourceIterator<TypeVertex> iterator = null;

            if (!props().labels().isEmpty()) iterator = iterateLabels(graphMgr);
            if (!props().valueTypes().isEmpty()) iterator = iterateOrFilterValueTypes(graphMgr, iterator);
            if (props().isAbstract()) iterator = iterateOrFilterAbstract(graphMgr, iterator);
            if (props().regex().isPresent()) iterator = iterateAndFilterRegex(graphMgr, iterator);
            if (iterator == null) {
                if (mustBeAttributeType()) return graphMgr.schema().attributeTypes();
                else if (mustBeRelationType()) return graphMgr.schema().relationTypes();
                else if (mustBeRoleType()) return graphMgr.schema().roleTypes();
                else if (mustBeThingType()) return graphMgr.schema().thingTypes();
                else iterator = link(graphMgr.schema().thingTypes(), graphMgr.schema().roleTypes());
            }
            return iterator;
        }

        ResourceIterator<TypeVertex> filter(ResourceIterator<TypeVertex> iterator) {
            if (!props().labels().isEmpty()) iterator = filterLabels(iterator);
            if (!props().valueTypes().isEmpty()) iterator = filterValueTypes(iterator);
            if (props().isAbstract()) iterator = filterAbstract(iterator);
            if (props().regex().isPresent()) iterator = filterRegex(iterator);
            return iterator;
        }

        private boolean mustBeAttributeType() {
            return iterate(outs()).anyMatch(ProcedureEdge::onlyStartsFromAttributeType);
        }

        private boolean mustBeRelationType() {
            return iterate(outs()).anyMatch(ProcedureEdge::onlyStartsFromRelationType);
        }

        private boolean mustBeRoleType() {
            return iterate(outs()).anyMatch(ProcedureEdge::onlyStartsFromRoleType);
        }

        private boolean mustBeThingType() {
            return iterate(outs()).anyMatch(ProcedureEdge::onlyStartsFromThingType);
        }

        private ResourceIterator<TypeVertex> iterateLabels(GraphManager graphMgr) {
            return iterate(props().labels()).map(l -> assertTypeNotNull(graphMgr.schema().getType(l), l));
        }

        private ResourceIterator<TypeVertex> filterLabels(ResourceIterator<TypeVertex> iterator) {
            assert !props().labels().isEmpty();
            return iterator.filter(t -> props().labels().contains(t.properLabel()));
        }

        private ResourceIterator<TypeVertex> iterateOrFilterValueTypes(GraphManager graphMgr,
                                                                       ResourceIterator<TypeVertex> iterator) {
            assert !props().valueTypes().isEmpty();
            if (iterator == null) {
                List<ResourceIterator<TypeVertex>> iterators = new ArrayList<>();
                for (Encoding.ValueType valueType : props().valueTypes()) {
                    iterators.add(graphMgr.schema().attributeTypes(valueType));
                }
                return link(iterators);
            } else return filterValueTypes(iterator);
        }

        private ResourceIterator<TypeVertex> filterValueTypes(ResourceIterator<TypeVertex> iterator) {
            assert !props().valueTypes().isEmpty();
            return iterator.filter(t -> props().valueTypes().contains(t.valueType()));
        }

        private ResourceIterator<TypeVertex> iterateOrFilterAbstract(GraphManager graphMgr,
                                                                     ResourceIterator<TypeVertex> iterator) {
            if (iterator == null) return graphMgr.schema().thingTypes().filter(TypeVertex::isAbstract);
            else return filterAbstract(iterator);
        }

        private ResourceIterator<TypeVertex> filterAbstract(ResourceIterator<TypeVertex> iterator) {
            return iterator.filter(TypeVertex::isAbstract);
        }

        private ResourceIterator<TypeVertex> iterateAndFilterRegex(GraphManager graphMgr,
                                                                   ResourceIterator<TypeVertex> iterator) {
            if (iterator == null) iterator = graphMgr.schema().attributeTypes(STRING);
            return filterRegex(iterator);
        }

        private ResourceIterator<TypeVertex> filterRegex(ResourceIterator<TypeVertex> iterator) {
            return iterator.filter(at -> at.regex() != null && at.regex().pattern().equals(props().regex().get()));
        }

        @Override
        public boolean isType() { return true; }

        @Override
        public ProcedureVertex.Type asType() { return this; }
    }
}

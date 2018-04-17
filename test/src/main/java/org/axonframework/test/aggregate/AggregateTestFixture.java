/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.test.aggregate;

import org.axonframework.commandhandling.*;
import org.axonframework.commandhandling.model.Aggregate;
import org.axonframework.commandhandling.model.AggregateNotFoundException;
import org.axonframework.commandhandling.model.Repository;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.Registration;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventsourcing.*;
import org.axonframework.eventsourcing.eventstore.*;
import org.axonframework.messaging.*;
import org.axonframework.messaging.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.annotation.HandlerDefinition;
import org.axonframework.messaging.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.IgnoreField;
import org.axonframework.test.matchers.MatchAllFieldFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.String.format;
import static org.axonframework.common.ReflectionUtils.*;

/**
 * A test fixture that allows the execution of given-when-then style test cases. For detailed usage information, see
 * {@link FixtureConfiguration}.
 *
 * @param <T> The type of Aggregate tested in this Fixture
 * @author Allard Buijze
 * @since 0.6
 */
public class AggregateTestFixture<T> implements FixtureConfiguration<T>, TestExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AggregateTestFixture.class);
    private final Class<T> aggregateType;
    private final SimpleCommandBus commandBus;
    private final List<MessageDispatchInterceptor<CommandMessage<?>>> commandDispatchInterceptors = new ArrayList<>();
    private final List<MessageHandlerInterceptor<CommandMessage<?>>> commandHandlerInterceptors = new ArrayList<>();
    private final EventStore eventStore;
    private final List<FieldFilter> fieldFilters = new ArrayList<>();
    private final List<Object> resources = new ArrayList<>();
    private Repository<T> repository;
    private String aggregateIdentifier;
    private Deque<DomainEventMessage<?>> givenEvents;
    private Deque<DomainEventMessage<?>> storedEvents;
    private List<EventMessage<?>> publishedEvents;
    private long sequenceNumber = 0;
    private Aggregate<T> workingAggregate;
    private boolean reportIllegalStateChange = true;
    private boolean explicitCommandHandlersSet;
    private MultiParameterResolverFactory parameterResolverFactory;
    private final List<HandlerDefinition> handlerDefinitions;
    private final List<HandlerEnhancerDefinition> handlerEnhancerDefinitions;

    /**
     * Initializes a new given-when-then style test fixture for the given {@code aggregateType}.
     *
     * @param aggregateType The aggregate to initialize the test fixture for
     */
    public AggregateTestFixture(Class<T> aggregateType) {
        commandBus = new SimpleCommandBus();
        eventStore = new RecordingEventStore();
        resources.add(commandBus);
        resources.add(eventStore);
        this.aggregateType = aggregateType;
        clearGivenWhenState();
        parameterResolverFactory = MultiParameterResolverFactory.ordered(
                new SimpleResourceParameterResolverFactory(resources),
                ClasspathParameterResolverFactory.forClass(aggregateType));
        handlerDefinitions = new ArrayList<>();
        ServiceLoader.load(HandlerDefinition.class).forEach(handlerDefinitions::add);
        handlerEnhancerDefinitions = new ArrayList<>();
        ServiceLoader.load(HandlerEnhancerDefinition.class).forEach(handlerEnhancerDefinitions::add);
    }

    @Override
    public FixtureConfiguration<T> registerRepository(EventSourcingRepository<T> eventSourcingRepository) {
        this.repository = new IdentifierValidatingRepository<>(eventSourcingRepository);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerAggregateFactory(AggregateFactory<T> aggregateFactory) {
        return registerRepository(new EventSourcingRepository<>(
                aggregateFactory, eventStore,
                MultiParameterResolverFactory.ordered(
                        new SimpleResourceParameterResolverFactory(resources),
                        ClasspathParameterResolverFactory.forClass(aggregateType)),
                NoSnapshotTriggerDefinition.INSTANCE));
    }

    @Override
    public synchronized FixtureConfiguration<T> registerAnnotatedCommandHandler(final Object annotatedCommandHandler) {
        registerAggregateCommandHandlers();
        explicitCommandHandlersSet = true;
        AnnotationCommandHandlerAdapter adapter = new AnnotationCommandHandlerAdapter(
                annotatedCommandHandler, parameterResolverFactory, handlerDefinitions, handlerEnhancerDefinitions);
        adapter.subscribe(commandBus);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerCommandHandler(Class<?> payloadType,
                                                          MessageHandler<CommandMessage<?>> commandHandler) {
        return registerCommandHandler(payloadType.getName(), commandHandler);
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public FixtureConfiguration<T> registerCommandHandler(String commandName,
                                                          MessageHandler<CommandMessage<?>> commandHandler) {
        registerAggregateCommandHandlers();
        explicitCommandHandlersSet = true;
        commandBus.subscribe(commandName, commandHandler);
        return this;
    }


    @Override
    public FixtureConfiguration<T> registerInjectableResource(Object resource) {
        if (explicitCommandHandlersSet) {
            throw new FixtureExecutionException("Cannot inject resources after command handler has been created. " +
                                                        "Configure all resource before calling " +
                                                        "registerCommandHandler() or " +
                                                        "registerAnnotatedCommandHandler()");
        }
        resources.add(resource);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerCommandDispatchInterceptor(
            MessageDispatchInterceptor<CommandMessage<?>> commandDispatchInterceptor) {
        commandDispatchInterceptors.add(commandDispatchInterceptor);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerCommandHandlerInterceptor(
            MessageHandlerInterceptor<CommandMessage<?>> commandHanderInterceptor) {
        commandHandlerInterceptors.add(commandHanderInterceptor);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerFieldFilter(FieldFilter fieldFilter) {
        this.fieldFilters.add(fieldFilter);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerIgnoredField(Class<?> declaringClass, String fieldName) {
        return registerFieldFilter(new IgnoreField(declaringClass, fieldName));
    }

    @Override
    public FixtureConfiguration<T> registerHandlerDefinition(HandlerDefinition handlerDefinition) {
        handlerDefinitions.add(handlerDefinition);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerHandlerEnhancerDefinition(
            HandlerEnhancerDefinition handlerEnhancerDefinition) {
        handlerEnhancerDefinitions.add(handlerEnhancerDefinition);
        return this;
    }

    @Override
    public TestExecutor given(Object... domainEvents) {
        return given(Arrays.asList(domainEvents));
    }

    @Override
    public TestExecutor andGiven(Object... domainEvents) {
        return andGiven(Arrays.asList(domainEvents));
    }

    @Override
    public TestExecutor givenNoPriorActivity() {
        return given(Collections.emptyList());
    }

    @Override
    public TestExecutor given(List<?> domainEvents) {
        ensureRepositoryConfiguration();
        clearGivenWhenState();
        return andGiven(domainEvents);
    }

    @Override
    public TestExecutor andGiven(List<?> domainEvents) {
        for (Object event : domainEvents) {
            Object payload = event;
            MetaData metaData = null;
            if (event instanceof Message) {
                payload = ((Message) event).getPayload();
                metaData = ((Message) event).getMetaData();
            }
            this.givenEvents.add(new GenericDomainEventMessage<>(aggregateType.getSimpleName(), aggregateIdentifier,
                    sequenceNumber++, payload, metaData));
        }
        return this;
    }

    @Override
    public TestExecutor givenCommands(Object... commands) {
        return givenCommands(Arrays.asList(commands));
    }

    @Override
    public TestExecutor andGivenCommands(Object... commands) {
        return andGivenCommands(Arrays.asList(commands));
    }

    @Override
    public TestExecutor givenCommands(List<?> commands) {
        clearGivenWhenState();
        return andGivenCommands(commands);
    }

    @Override
    public TestExecutor andGivenCommands(List<?> commands) {
        finalizeConfiguration();
        for (Object command : commands) {
            ExecutionExceptionAwareCallback callback = new ExecutionExceptionAwareCallback();
            commandBus.dispatch(GenericCommandMessage.asCommandMessage(command), callback);
            callback.assertSuccessful();
            givenEvents.addAll(storedEvents);
            storedEvents.clear();
        }
        publishedEvents.clear();
        return this;
    }

    @Override
    public ResultValidator when(Object command) {
        return when(command, MetaData.emptyInstance());
    }

    @SuppressWarnings("unchecked")
    @Override
    public ResultValidator when(Object command, Map<String, ?> metaData) {
        commandHandlerInterceptors.add(new AggregateRegisteringInterceptor());
        finalizeConfiguration();
        final MatchAllFieldFilter fieldFilter = new MatchAllFieldFilter(fieldFilters);
        ResultValidatorImpl resultValidator = new ResultValidatorImpl(publishedEvents, fieldFilter);

        commandBus.dispatch(GenericCommandMessage.asCommandMessage(command).andMetaData(metaData), resultValidator);

        detectIllegalStateChanges(fieldFilter);
        resultValidator.assertValidRecording();
        return resultValidator;
    }

    private void ensureRepositoryConfiguration() {
        if (repository == null) {
            registerRepository(new EventSourcingRepository<>(new GenericAggregateFactory<T>(aggregateType),
                                                             eventStore, parameterResolverFactory,
                                                             NoSnapshotTriggerDefinition.INSTANCE));
        }
    }

    private void finalizeConfiguration() {
        registerAggregateCommandHandlers();
        registerCommandInterceptors();
        explicitCommandHandlersSet = true;
    }

    private void registerAggregateCommandHandlers() {
        ensureRepositoryConfiguration();
        if (!explicitCommandHandlersSet) {
            AggregateAnnotationCommandHandler<T> handler =
                    new AggregateAnnotationCommandHandler<>(aggregateType, repository,
                                                            new AnnotationCommandTargetResolver(),
                                                            parameterResolverFactory);
            handler.subscribe(commandBus);
        }
    }

    private void registerCommandInterceptors() {
        commandDispatchInterceptors.forEach(commandBus::registerDispatchInterceptor);
        commandHandlerInterceptors.forEach(commandBus::registerHandlerInterceptor);
    }

    private void detectIllegalStateChanges(MatchAllFieldFilter fieldFilter) {
        logger.debug("Starting separate Unit of Work for the purpose of checking illegal state changes in Aggregate");
        if (aggregateIdentifier != null && workingAggregate != null && reportIllegalStateChange) {
            UnitOfWork<?> uow = DefaultUnitOfWork.startAndGet(null);
            try {
                Aggregate<T> aggregate2 = repository.load(aggregateIdentifier);
                if (workingAggregate.isDeleted()) {
                    throw new AxonAssertionError("The working aggregate was considered deleted, " +
                                                         "but the Repository still contains a non-deleted copy of " +
                                                         "the aggregate. Make sure the aggregate explicitly marks " +
                                                         "itself as deleted in an EventHandler.");
                }
                assertValidWorkingAggregateState(aggregate2, fieldFilter);
            } catch (AggregateNotFoundException notFound) {
                if (!workingAggregate.isDeleted()) {
                    throw new AxonAssertionError("The working aggregate was not considered deleted, " //NOSONAR
                                                         + "but the Repository cannot recover the state of the " +
                                                         "aggregate, as it is considered deleted there.");
                }
            } catch (Exception e) {
                throw new FixtureExecutionException("An Exception occurred while reconstructing the Aggregate from " +
                                                            "given and published events. This may be an indication " +
                                                            "that the aggregate cannot be recreated from its events.",
                                                    e);
            } finally {
                // rollback to prevent changes bing pushed to event store
                uow.rollback();
            }
        }
    }

    private void assertValidWorkingAggregateState(Aggregate<T> eventSourcedAggregate, MatchAllFieldFilter fieldFilter) {
        HashSet<ComparationEntry> comparedEntries = new HashSet<>();
        if (!workingAggregate.rootType().equals(eventSourcedAggregate.rootType())) {
            throw new AxonAssertionError(String.format("The aggregate loaded based on the generated events seems to " +
                                                               "be of another type than the original.\n" +
                                                               "Working type: <%s>\nEvent Sourced type: <%s>",
                                                       workingAggregate.rootType().getName(),
                                                       eventSourcedAggregate.rootType().getName()));
        }
        ensureValuesEqual(workingAggregate.invoke(Function.identity()),
                          eventSourcedAggregate.invoke(Function.identity()), eventSourcedAggregate.rootType().getName(),
                          comparedEntries, fieldFilter);
    }

    private void ensureValuesEqual(Object workingValue, Object eventSourcedValue, String propertyPath,
                                   Set<ComparationEntry> comparedEntries, FieldFilter fieldFilter) {
        if (explicitlyUnequal(workingValue, eventSourcedValue)) {
            throw new AxonAssertionError(format("Illegal state change detected! " +
                                                        "Property \"%s\" has different value when sourcing events.\n" +
                                                        "Working aggregate value:     <%s>\n" +
                                                        "Value after applying events: <%s>", propertyPath, workingValue,
                                                eventSourcedValue));
        } else if (workingValue != null && comparedEntries.add(new ComparationEntry(workingValue, eventSourcedValue)) &&
                !hasEqualsMethod(workingValue.getClass())) {
            for (Field field : fieldsOf(workingValue.getClass())) {
                if (fieldFilter.accept(field) && !Modifier.isStatic(field.getModifiers()) &&
                        !Modifier.isTransient(field.getModifiers())) {
                    ensureAccessible(field);
                    String newPropertyPath = propertyPath + "." + field.getName();

                    Object workingFieldValue = ReflectionUtils.getFieldValue(field, workingValue);
                    Object eventSourcedFieldValue = ReflectionUtils.getFieldValue(field, eventSourcedValue);
                    ensureValuesEqual(workingFieldValue, eventSourcedFieldValue, newPropertyPath, comparedEntries,
                                      fieldFilter);
                }
            }
        }
    }

    private void clearGivenWhenState() {
        storedEvents = new LinkedList<>();
        publishedEvents = new ArrayList<>();
        givenEvents = new LinkedList<>();
        sequenceNumber = 0;
    }

    @Override
    public void setReportIllegalStateChange(boolean reportIllegalStateChange) {
        this.reportIllegalStateChange = reportIllegalStateChange;
    }

    @Override
    public CommandBus getCommandBus() {
        return commandBus;
    }

    @Override
    public EventBus getEventBus() {
        return eventStore;
    }

    @Override
    public EventStore getEventStore() {
        return eventStore;
    }

    @Override
    public Repository<T> getRepository() {
        ensureRepositoryConfiguration();
        return repository;
    }

    private static class ComparationEntry {

        private final Object workingObject;
        private final Object eventSourceObject;

        public ComparationEntry(Object workingObject, Object eventSourceObject) {
            this.workingObject = workingObject;
            this.eventSourceObject = eventSourceObject;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ComparationEntry that = (ComparationEntry) o;
            return Objects.equals(workingObject, that.workingObject) &&
                    Objects.equals(eventSourceObject, that.eventSourceObject);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workingObject, eventSourceObject);
        }
    }

    private static class IdentifierValidatingRepository<T> implements Repository<T> {

        private final Repository<T> delegate;

        public IdentifierValidatingRepository(Repository<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Aggregate<T> newInstance(Callable<T> factoryMethod) throws Exception {
            return delegate.newInstance(factoryMethod);
        }

        @Override
        public Aggregate<T> load(String aggregateIdentifier, Long expectedVersion) {
            Aggregate<T> aggregate = delegate.load(aggregateIdentifier, expectedVersion);
            validateIdentifier(aggregateIdentifier, aggregate);
            return aggregate;
        }

        @Override
        public Aggregate<T> load(String aggregateIdentifier) {
            Aggregate<T> aggregate = delegate.load(aggregateIdentifier, null);
            validateIdentifier(aggregateIdentifier, aggregate);
            return aggregate;
        }

        private void validateIdentifier(String aggregateIdentifier, Aggregate<T> aggregate) {
            if (aggregateIdentifier != null && !aggregateIdentifier.equals(aggregate.identifierAsString())) {
                throw new AssertionError(String.format(
                        "The aggregate used in this fixture was initialized with an identifier different than " +
                                "the one used to load it. Loaded [%s], but actual identifier is [%s].\n" +
                                "Make sure the identifier passed in the Command matches that of the given Events.",
                        aggregateIdentifier, aggregate.identifierAsString()));
            }
        }
    }

    private class RecordingEventStore implements EventStore {

        @Override
        public DomainEventStream readEvents(String identifier) {
            if (aggregateIdentifier != null && !aggregateIdentifier.equals(identifier)) {
                throw new EventStoreException("You probably want to use aggregateIdentifier() on your fixture " +
                                                      "to get the aggregate identifier to use");
            } else if (aggregateIdentifier == null) {
                aggregateIdentifier = identifier;
                injectAggregateIdentifier();
            }
            List<DomainEventMessage<?>> allEvents = new ArrayList<>(givenEvents);
            allEvents.addAll(storedEvents);
            if (allEvents.isEmpty()) {
                throw new AggregateNotFoundException(identifier,
                                                     "No 'given' events were configured for this aggregate, " +
                                                             "nor have any events been stored.");
            }
            return DomainEventStream.of(allEvents);
        }

        @Override
        public void publish(List<? extends EventMessage<?>> events) {
            if (CurrentUnitOfWork.isStarted()) {
                CurrentUnitOfWork.get().onPrepareCommit(u -> doAppendEvents(events));
            } else {
                doAppendEvents(events);
            }
        }

        protected void doAppendEvents(List<? extends EventMessage<?>> events) {
            publishedEvents.addAll(events);
            events.stream().filter(DomainEventMessage.class::isInstance).map(e -> (DomainEventMessage<?>) e)
                    .forEach(event -> {
                        if (aggregateIdentifier == null) {
                            aggregateIdentifier = event.getAggregateIdentifier();
                            injectAggregateIdentifier();
                        }

                        DomainEventMessage lastEvent = (storedEvents.isEmpty() ? givenEvents : storedEvents).peekLast();

                        if (lastEvent != null) {
                            if (!lastEvent.getAggregateIdentifier().equals(event.getAggregateIdentifier())) {
                                throw new EventStoreException(
                                        "Writing events for an unexpected aggregate. This could " +
                                                "indicate that a wrong aggregate is being triggered.");
                            } else if (lastEvent.getSequenceNumber() != event.getSequenceNumber() - 1) {
                                throw new EventStoreException(format("Unexpected sequence number on stored event. " +
                                                                             "Expected %s, but got %s.",
                                                                     lastEvent.getSequenceNumber() + 1,
                                                                     event.getSequenceNumber()));
                            }
                        }
                        storedEvents.add(event);
                    });
        }

        private void injectAggregateIdentifier() {
            List<DomainEventMessage> oldEvents = new ArrayList<>(givenEvents);
            givenEvents.clear();
            for (DomainEventMessage oldEvent : oldEvents) {
                if (oldEvent.getAggregateIdentifier() == null) {
                    givenEvents.add(new GenericDomainEventMessage<>(oldEvent.getType(), aggregateIdentifier,
                                                                    oldEvent.getSequenceNumber(), oldEvent.getPayload(),
                                                                    oldEvent.getMetaData(), oldEvent.getIdentifier(),
                                                                    oldEvent.getTimestamp()));
                } else {
                    givenEvents.add(oldEvent);
                }
            }
        }

        @Override
        public TrackingEventStream openStream(TrackingToken trackingToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void storeSnapshot(DomainEventMessage<?> snapshot) {
        }

        @Override
        public Registration subscribe(Consumer<List<? extends EventMessage<?>>> eventProcessor) {
            return () -> true;
        }

        @Override
        public Registration registerDispatchInterceptor(
                MessageDispatchInterceptor<? super EventMessage<?>> dispatchInterceptor) {
            return () -> true;
        }
    }

    private class AggregateRegisteringInterceptor implements MessageHandlerInterceptor<CommandMessage<?>> {

        @Override
        public Object handle(UnitOfWork<? extends CommandMessage<?>> unitOfWork,
                             InterceptorChain interceptorChain) throws Exception {
            unitOfWork.onPrepareCommit(u -> {
                Set<Aggregate<T>> aggregates = u.getResource("ManagedAggregates");
                if (aggregates != null && aggregates.size() == 1) {
                    workingAggregate = aggregates.iterator().next();
                }
            });
            return interceptorChain.proceed();
        }
    }

    private class ExecutionExceptionAwareCallback implements CommandCallback<Object, Object> {

        private FixtureExecutionException exception;

        @Override
        public void onSuccess(CommandMessage<?> commandMessage, Object result) {
        }

        @Override
        public void onFailure(CommandMessage<?> commandMessage, Throwable cause) {
            if (cause instanceof FixtureExecutionException) {
                this.exception = (FixtureExecutionException) cause;
            } else {
                this.exception = new FixtureExecutionException("Failed to execute givenCommands", cause);
            }
        }

        public void assertSuccessful() {
            if (exception != null) {
                throw exception;
            }
        }
    }
}

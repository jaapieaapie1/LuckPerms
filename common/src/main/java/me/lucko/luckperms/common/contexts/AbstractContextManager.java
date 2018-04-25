/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.contexts;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.caching.MetaContexts;
import me.lucko.luckperms.api.context.ContextCalculator;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.api.context.StaticContextCalculator;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

/**
 * An abstract implementation of {@link ContextManager} which caches content lookups.
 *
 * @param <T> the calculator type
 */
public abstract class AbstractContextManager<T> implements ContextManager<T> {

    protected final LuckPermsPlugin plugin;
    private final Class<T> subjectClass;

    private final List<ContextCalculator<? super T>> calculators = new CopyOnWriteArrayList<>();
    private final List<StaticContextCalculator> staticCalculators = new CopyOnWriteArrayList<>();

    // caches context lookups
    private final LoadingCache<T, Contexts> lookupCache = Caffeine.newBuilder()
            .expireAfterWrite(50L, TimeUnit.MILLISECONDS) // expire roughly every tick
            .build(new Loader());

    // caches static context lookups
    @SuppressWarnings("Guava")
    private final Supplier<Contexts> staticLookupCache = Suppliers.memoizeWithExpiration(new StaticLoader(), 50L, TimeUnit.MILLISECONDS);

    protected AbstractContextManager(LuckPermsPlugin plugin, Class<T> subjectClass) {
        this.plugin = plugin;
        this.subjectClass = subjectClass;
    }

    @Override
    public List<ContextCalculator<? super T>> getCalculators() {
        return ImmutableList.copyOf(this.calculators);
    }

    @Override
    public List<StaticContextCalculator> getStaticCalculators() {
        return ImmutableList.copyOf(this.staticCalculators);
    }

    @Override
    public Class<T> getSubjectClass() {
        return this.subjectClass;
    }

    @Override
    public ImmutableContextSet getApplicableContext(T subject) {
        if (subject == null) {
            throw new NullPointerException("subject");
        }

        // this is actually already immutable, but the Contexts method signature returns the interface.
        // using the makeImmutable method is faster than casting
        return getApplicableContexts(subject).getContexts().makeImmutable();
    }

    @Override
    public Contexts getApplicableContexts(T subject) {
        if (subject == null) {
            throw new NullPointerException("subject");
        }
        return this.lookupCache.get(subject);
    }

    @Override
    public ImmutableContextSet getStaticContext() {
        // this is actually already immutable, but the Contexts method signature returns the interface.
        // using the makeImmutable method is faster than casting
        return getStaticContexts().getContexts().makeImmutable();
    }

    @Override
    public Contexts getStaticContexts() {
        return this.staticLookupCache.get();
    }

    @Override
    public Optional<String> getStaticContextString() {
        Set<Map.Entry<String, String>> entries = getStaticContext().toSet();
        if (entries.isEmpty()) {
            return Optional.empty();
        }

        // effectively: if entries contains any non-server keys
        if (entries.stream().anyMatch(pair -> !pair.getKey().equals(Contexts.SERVER_KEY))) {
            // return all entries in 'key=value' form
            return Optional.of(entries.stream().map(pair -> pair.getKey() + "=" + pair.getValue()).collect(Collectors.joining(";")));
        } else {
            // just return the server ids, without the 'server='
            return Optional.of(entries.stream().map(Map.Entry::getValue).collect(Collectors.joining(";")));
        }
    }

    @Override
    public Contexts formContexts(ImmutableContextSet contextSet) {
        return Contexts.of(
                contextSet,
                this.plugin.getConfiguration().get(ConfigKeys.INCLUDING_GLOBAL_PERMS),
                this.plugin.getConfiguration().get(ConfigKeys.INCLUDING_GLOBAL_WORLD_PERMS),
                true,
                this.plugin.getConfiguration().get(ConfigKeys.APPLYING_GLOBAL_GROUPS),
                this.plugin.getConfiguration().get(ConfigKeys.APPLYING_GLOBAL_WORLD_GROUPS),
                false
        );
    }

    @Override
    public MetaContexts formMetaContexts(Contexts contexts) {
        return new MetaContexts(
                contexts,
                this.plugin.getConfiguration().get(ConfigKeys.PREFIX_FORMATTING_OPTIONS),
                this.plugin.getConfiguration().get(ConfigKeys.SUFFIX_FORMATTING_OPTIONS)
        );
    }

    @Override
    public void registerCalculator(ContextCalculator<? super T> calculator) {
        // calculators registered first should have priority (and be checked last.)
        this.calculators.add(0, calculator);
    }

    @Override
    public void registerStaticCalculator(StaticContextCalculator calculator) {
        registerCalculator(calculator);
        this.staticCalculators.add(0, calculator);
    }

    @Override
    public void invalidateCache(T subject) {
        if (subject == null) {
            throw new NullPointerException("subject");
        }

        this.lookupCache.invalidate(subject);
    }

    private final class Loader implements CacheLoader<T, Contexts> {
        @Override
        public Contexts load(@Nonnull T subject) {
            MutableContextSet accumulator = MutableContextSet.create();

            for (ContextCalculator<? super T> calculator : AbstractContextManager.this.calculators) {
                try {
                    MutableContextSet ret = calculator.giveApplicableContext(subject, accumulator);
                    //noinspection ConstantConditions
                    if (ret == null) {
                        throw new IllegalStateException(calculator.getClass() + " returned a null context set");
                    }
                    accumulator = ret;
                } catch (Exception e) {
                    AbstractContextManager.this.plugin.getLogger().warn("An exception was thrown by " + getCalculatorClass(calculator) + " whilst calculating the context of subject " + subject);
                    e.printStackTrace();
                }
            }

            return formContexts(subject, accumulator.makeImmutable());
        }
    }

    private final class StaticLoader implements Supplier<Contexts> {
        @Override
        public Contexts get() {
            MutableContextSet accumulator = MutableContextSet.create();

            for (StaticContextCalculator calculator : AbstractContextManager.this.staticCalculators) {
                try {
                    MutableContextSet ret = calculator.giveApplicableContext(accumulator);
                    //noinspection ConstantConditions
                    if (ret == null) {
                        throw new IllegalStateException(calculator.getClass() + " returned a null context set");
                    }
                    accumulator = ret;
                } catch (Exception e) {
                    AbstractContextManager.this.plugin.getLogger().warn("An exception was thrown by " + getCalculatorClass(calculator) + " whilst calculating static contexts");
                    e.printStackTrace();
                }
            }

            return formContexts(accumulator.makeImmutable());
        }
    }

    private static String getCalculatorClass(ContextCalculator<?> calculator) {
        Class<?> calculatorClass;
        if (calculator instanceof ProxiedContextCalculator) {
            calculatorClass = ((ProxiedContextCalculator) calculator).getDelegate().getClass();
        } else {
            calculatorClass = calculator.getClass();
        }
        return calculatorClass.getName();
    }

}

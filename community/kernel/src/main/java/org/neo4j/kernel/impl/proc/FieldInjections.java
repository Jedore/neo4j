/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.proc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.proc.CallableProcedure;
import org.neo4j.procedure.Context;

/**
 * Injects annotated fields with appropriate values.
 */
public class FieldInjections
{
    private final ComponentRegistry components;

    public FieldInjections( ComponentRegistry components )
    {
        this.components = components;
    }

    /**
     * On calling apply, injects the `value` for the field `field` on the provided `object`.
     */
    public static class FieldSetter
    {
        private final Field field;
        private final MethodHandle setter;
        private final Function<CallableProcedure.Context,?> supplier;

        public FieldSetter( Field field, MethodHandle setter, Function<CallableProcedure.Context,?> supplier )
        {
            this.field = field;
            this.setter = setter;
            this.supplier = supplier;
        }

        void apply( CallableProcedure.Context ctx, Object object ) throws ProcedureException
        {
            try
            {
                setter.invoke( object, supplier.apply( ctx ) );
            }
            catch ( Throwable e )
            {
                throw new ProcedureException( Status.Procedure.CallFailed, e,
                        "Unable to inject component to field `%s`, please ensure it is public and non-final: %s",
                        field.getName(), e.getMessage() );
            }
        }
    }

    /**
     * For each annotated field in the provided class, creates a `FieldSetter`.
     * @param cls The class where injection should happen.
     * @return A list of `FieldSetters`
     * @throws ProcedureException if the type of the injected field does not match what has been registered.
     */
    public List<FieldSetter> setters( Class<?> cls ) throws ProcedureException
    {
        List<FieldSetter> setters = new LinkedList<>();
        Class<?> currentClass = cls;

        do
        {
            for ( Field field : currentClass.getDeclaredFields() )
            {
                if ( Modifier.isStatic( field.getModifiers() ) )
                {
                    if( field.isAnnotationPresent( Context.class ))
                    {
                        throw new ProcedureException( Status.Procedure.FailedRegistration,
                                "The field `%s` in the class named `%s` is annotated as a @Context field,\n" +
                                "but it is static. @Context fields must be public, non-final and non-static,\n" +
                                "because they are reset each time a procedure is invoked.",
                                field.getName(), cls.getSimpleName() );
                    }
                    continue;
                }

                assertValidForInjection( cls, field );
                setters.add( createInjector( cls, field ) );
            }
        } while( (currentClass = currentClass.getSuperclass()) != null );

        return setters;
    }

    private FieldSetter createInjector( Class<?> cls, Field field ) throws ProcedureException
    {
        try
        {
            Function<CallableProcedure.Context,?> supplier = components.supplierFor( field.getType() );
            if( supplier == null )
            {
                throw new ProcedureException( Status.Procedure.FailedRegistration,
                        "Unable to set up injection for procedure `%s`, the field `%s` " +
                        "has type `%s` which is not a known injectable component.",
                        cls.getSimpleName(), field.getName(), field.getType());
            }

            MethodHandle setter = MethodHandles.lookup().unreflectSetter( field );
            return new FieldSetter( field, setter, supplier );
        }
        catch ( IllegalAccessException e )
        {
            throw new ProcedureException( Status.Procedure.FailedRegistration,
                    "Unable to set up injection for `%s`, failed to access field `%s`: %s",
                    e, cls.getSimpleName(), field.getName(), e.getMessage() );
        }
    }

    private void assertValidForInjection( Class<?> cls, Field field ) throws ProcedureException
    {
        if( !field.isAnnotationPresent( Context.class ) )
        {
            throw new ProcedureException(  Status.Procedure.FailedRegistration,
                    "Field `%s` on `%s` is not annotated as a @Resource and is not static. " +
                    "If you want to store state along with your procedure, please use a static field.",
                    field.getName(), cls.getSimpleName() );
        }

        if( !Modifier.isPublic( field.getModifiers() ) || Modifier.isFinal( field.getModifiers() ) )
        {
            throw new ProcedureException(  Status.Procedure.FailedRegistration,
                    "Field `%s` on `%s` must be non-final and public.",
                    field.getName(), cls.getSimpleName() );

        }
    }
}

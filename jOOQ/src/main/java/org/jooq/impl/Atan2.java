/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.impl;

import static org.jooq.impl.DSL.*;
import static org.jooq.impl.Internal.*;
import static org.jooq.impl.Keywords.*;
import static org.jooq.impl.Names.*;
import static org.jooq.impl.SQLDataType.*;
import static org.jooq.impl.Tools.*;
import static org.jooq.impl.Tools.BooleanDataKey.*;
import static org.jooq.impl.Tools.DataExtendedKey.*;
import static org.jooq.impl.Tools.DataKey.*;
import static org.jooq.SQLDialect.*;

import org.jooq.*;
import org.jooq.Function1;
import org.jooq.Record;
import org.jooq.conf.*;
import org.jooq.impl.*;
import org.jooq.impl.QOM.*;
import org.jooq.tools.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import java.math.BigDecimal;


/**
 * The <code>ATAN2</code> statement.
 */
@SuppressWarnings({ "rawtypes", "unused" })
final class Atan2
extends
    AbstractField<BigDecimal>
implements
    QOM.Atan2
{

    final Field<? extends Number> x;
    final Field<? extends Number> y;

    Atan2(
        Field<? extends Number> x,
        Field<? extends Number> y
    ) {
        super(
            N_ATAN2,
            allNotNull(NUMERIC, x, y)
        );

        this.x = nullSafeNotNull(x, INTEGER);
        this.y = nullSafeNotNull(y, INTEGER);
    }

    // -------------------------------------------------------------------------
    // XXX: QueryPart API
    // -------------------------------------------------------------------------

    @Override
    public final void accept(Context<?> ctx) {
        switch (ctx.family()) {














            default:
                ctx.visit(function(N_ATAN2, getDataType(), x, y));
                break;
        }
    }














    // -------------------------------------------------------------------------
    // XXX: Query Object Model
    // -------------------------------------------------------------------------

    @Override
    public final Field<? extends Number> $x() {
        return x;
    }

    @Override
    public final Field<? extends Number> $y() {
        return y;
    }

    @Override
    public final QOM.Atan2 $x(Field<? extends Number> newValue) {
        return $constructor().apply(newValue, $y());
    }

    @Override
    public final QOM.Atan2 $y(Field<? extends Number> newValue) {
        return $constructor().apply($x(), newValue);
    }

    public final Function2<? super Field<? extends Number>, ? super Field<? extends Number>, ? extends QOM.Atan2> $constructor() {
        return (a1, a2) -> new Atan2(a1, a2);
    }
























    // -------------------------------------------------------------------------
    // XXX: The Object API
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object that) {
        if (that instanceof QOM.Atan2) { QOM.Atan2 o = (QOM.Atan2) that;
            return
                StringUtils.equals($x(), o.$x()) &&
                StringUtils.equals($y(), o.$y())
            ;
        }
        else
            return super.equals(that);
    }
}

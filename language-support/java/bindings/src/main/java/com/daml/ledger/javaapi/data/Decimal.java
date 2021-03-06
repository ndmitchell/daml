// Copyright (c) 2019 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.javaapi.data;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.math.BigDecimal;

@Deprecated
public class Decimal extends Numeric {

    public Decimal(@NonNull BigDecimal value) {
        super(value);
    }
}

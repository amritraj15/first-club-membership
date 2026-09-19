package com.firstclub.membership.domain;

/** Whether a tier's criteria must ALL be satisfied, or ANY one of them, to qualify.
 *  The spec phrases criteria as alternatives ("based on criteria like X, Y, or Z"), which reads
 *  as ANY/OR - but we make it configurable per tier rather than hardcoding that reading, since a
 *  future tier might legitimately want AND semantics (e.g. Platinum requiring both order volume
 *  AND a cohort). */
public enum CriteriaMatchMode {
    ANY,
    ALL
}

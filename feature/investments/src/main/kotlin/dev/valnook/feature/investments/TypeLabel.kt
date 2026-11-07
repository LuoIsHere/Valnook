package dev.valnook.feature.investments

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.valnook.domain.model.AssetTypeRules

/** A class's name as shown: the default one in the interface language, the others as the user named them. */
@Composable
internal fun typeLabel(name: String): String =
    if (AssetTypeRules.isDefaultName(name)) stringResource(R.string.instrument_type_default) else name

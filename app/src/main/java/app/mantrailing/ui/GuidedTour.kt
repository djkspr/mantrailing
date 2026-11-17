package app.mantrailing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Éléments de l'accueil que le tour guidé met en lumière. */
enum class TourTarget { TITLE, MENU, LAY, RECORD, LOCATE }

/** Une étape du tour : l'élément éclairé (null : carte centrée, sans élément) et l'explication. */
data class TourStep(val target: TourTarget?, val title: String, val text: String)

/** Tour guidé du premier lancement : de quoi faire une première piste sans chercher. */
val TOUR_STEPS = listOf(
    TourStep(
        null, "Bienvenue dans Mantrailing",
        "Le traceur enregistre la piste qu'il pose, puis la trace du chien est superposée à cette piste : on voit exactement comment il a travaillé.",
    ),
    TourStep(
        TourTarget.LAY, "Poser une piste",
        "Le traceur commence ici : son parcours est enregistré au GPS du téléphone, et il peut signaler un danger ou un point d'attention en chemin.",
    ),
    TourStep(
        TourTarget.RECORD, "Démarrer une recherche",
        "Quand la piste a vieilli, lancez la recherche : choisissez la piste à suivre, notez ce qui se passe, et le résultat s'affiche à l'arrivée.",
    ),
    TourStep(
        TourTarget.TITLE, "Le chien qui cherche",
        "Touchez son nom pour choisir le chien. Ajoutez vos chiens, et connectez leur collier Tractive ou Weenect, dans les paramètres.",
    ),
    TourStep(
        TourTarget.MENU, "Le menu",
        "Mes pistes, import d'un fichier GPX (Garmin…), séance de démonstration, paramètres et aide : tout est ici.",
    ),
    TourStep(
        TourTarget.LOCATE, "La carte",
        "« Me localiser » centre la carte sur vous ; le bouton du haut change de fond (plan IGN, photos aériennes, OpenStreetMap).",
    ),
)

/** Libellé du bouton qui avance : « C'est parti » à la dernière étape. */
fun tourNextLabel(step: Int): String = if (step >= TOUR_STEPS.lastIndex) "C'est parti" else "Suivant"

/** Place de la carte d'explication : au centre sans élément, sinon du côté où il y a le plus de place. */
enum class CardPlacement { CENTER, BELOW, ABOVE }

fun cardPlacement(target: Rect?, areaHeight: Float): CardPlacement = when {
    target == null -> CardPlacement.CENTER
    target.center.y < areaHeight / 2 -> CardPlacement.BELOW
    else -> CardPlacement.ABOVE
}

/** Positions des éléments éclairables, relevées pendant l'affichage de l'accueil (coordonnées de la fenêtre). */
class TourRegistry {
    val bounds = mutableStateMapOf<TourTarget, Rect>()
}

val LocalTourRegistry = staticCompositionLocalOf<TourRegistry?> { null }

/** Repère [target] pour le tour guidé ; sans tour en cours (aucun registre), ne fait rien. */
fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    val registry = LocalTourRegistry.current
    if (registry == null) this else onGloballyPositioned { registry.bounds[target] = it.boundsInRoot() }
}

/**
 * Tour guidé : l'écran est assombri, l'élément expliqué reste visible dans une découpe arrondie, et une carte
 * l'explique, placée au-dessus ou en dessous pour ne jamais le cacher. « Passer » ou la dernière étape : [onFinish].
 */
@Composable
fun GuidedTourOverlay(registry: TourRegistry, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var areaHeight by remember { mutableStateOf(0f) }
    val current = TOUR_STEPS[step]
    // Élément éclairé, dans les coordonnées de la couche du tour (marge autour pour la lisibilité).
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.toPx() }
    val target = current.target?.let { registry.bounds[it] }?.translate(-origin.x, -origin.y)?.inflate(margin)
    val placement = cardPlacement(target, areaHeight)

    Box(
        modifier
            .fillMaxSize()
            .testTag("tour")
            .onGloballyPositioned {
                origin = it.boundsInRoot().topLeft
                areaHeight = it.size.height.toFloat()
            }
            // Pendant le tour, l'accueil n'est pas utilisable : les touches s'arrêtent ici.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            target?.let {
                drawRoundRect(
                    Color.Black, topLeft = it.topLeft, size = it.size,
                    cornerRadius = CornerRadius(16.dp.toPx()), blendMode = BlendMode.Clear,
                )
            }
        }
        val cardModifier = with(density) {
            when (placement) {
                CardPlacement.CENTER -> Modifier.align(Alignment.Center)
                CardPlacement.BELOW -> Modifier.align(Alignment.TopCenter).padding(top = (target!!.bottom + margin).toDp())
                CardPlacement.ABOVE -> Modifier.align(Alignment.BottomCenter).padding(bottom = (areaHeight - target!!.top + margin).toDp())
            }
        }
        Card(cardModifier.safeDrawingPadding().padding(horizontal = 20.dp).widthIn(max = 480.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${step + 1} / ${TOUR_STEPS.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current.title, style = MaterialTheme.typography.titleLarge)
                Text(current.text, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (step < TOUR_STEPS.lastIndex) {
                        TextButton(onClick = onFinish, modifier = Modifier.testTag("tour-skip")) { Text("Passer") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (step < TOUR_STEPS.lastIndex) step++ else onFinish() },
                        modifier = Modifier.testTag("tour-next"),
                    ) { Text(tourNextLabel(step)) }
                }
            }
        }
    }
}

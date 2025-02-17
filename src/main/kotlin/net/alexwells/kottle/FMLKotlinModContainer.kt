package net.alexwells.kottle

import net.minecraftforge.eventbus.EventBusErrorMessage
import net.minecraftforge.eventbus.api.BusBuilder
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.IEventListener
import net.minecraftforge.fml.LifecycleEventProvider
import net.minecraftforge.fml.Logging.LOADING
import net.minecraftforge.fml.ModContainer
import net.minecraftforge.fml.ModLoadingException
import net.minecraftforge.fml.ModLoadingStage
import net.minecraftforge.forgespi.language.IModInfo
import net.minecraftforge.forgespi.language.ModFileScanData
import org.apache.logging.log4j.LogManager
import java.util.*
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Similar to FMLModContainer, with few changes:
 * - we do NOT instantiate mod class until CONSTRUCT phase, due to the fact kotlin object's
 *   init {} block becomes static {} after compilation, which leads to unexpected NPEs
 *   if trying to get currently loaded mod container for the event bus.
 * - during CONSTRUCT phase, instead of always doing .newInstance() on a class, we first
 *   check if that's kotlin object instance and return it instead if it is
 * - instead of using default AutomaticEventSubscriber, we're using KotlinAutomaticEventSubscriber
 */
class FMLKotlinModContainer(
        info: IModInfo,
        private val className: String,
        modClassLoader: ClassLoader,
        private val scanResults: ModFileScanData
) : ModContainer(info) {
    private val logger = LogManager.getLogger()

    val eventBus: IEventBus
    private var mod: Any? = null
    private val modClass: Class<*>

    init {
        logger.debug(LOADING, "Creating FMLModContainer instance for {} with classLoader {} & {}", className, modClassLoader, javaClass.classLoader)
        triggerMap[ModLoadingStage.CONSTRUCT] = dummy().andThen(::beforeEvent).andThen(::constructMod).andThen(::afterEvent)
        triggerMap[ModLoadingStage.CREATE_REGISTRIES] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        triggerMap[ModLoadingStage.LOAD_REGISTRIES] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        triggerMap[ModLoadingStage.COMMON_SETUP] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        triggerMap[ModLoadingStage.SIDED_SETUP] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        triggerMap[ModLoadingStage.ENQUEUE_IMC] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        triggerMap[ModLoadingStage.PROCESS_IMC] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        triggerMap[ModLoadingStage.COMPLETE] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        triggerMap[ModLoadingStage.GATHERDATA] = dummy().andThen(::beforeEvent).andThen(::fireEvent).andThen(::afterEvent)
        eventBus = BusBuilder.builder().setExceptionHandler(::onEventFailed).setTrackPhases(false).build()
        configHandler = Optional.of(Consumer { event -> eventBus.post(event) })

        val contextExtension = FMLKotlinModLoadingContext.Context(this)
        this.contextExtension = Supplier { contextExtension }

        try {
            // Here, we won't init the class, meaning static {} blocks (init {} in kotlin) won't get triggered
            // but we will still have to do it later, on CONSTRUCT phase.
            modClass = Class.forName(className, false, modClassLoader)
            logger.debug(LOADING, "Loaded modclass {} with {}", modClass.name, modClass.classLoader)
        } catch (e: Throwable) {
            logger.error(LOADING, "Failed to load class {}", className, e)
            throw ModLoadingException(info, ModLoadingStage.CONSTRUCT, "fml.modloading.failedtoloadmodclass", e)
        }
    }

    private fun constructMod(event: LifecycleEventProvider.LifecycleEvent) {
        try {
            logger.debug(LOADING, "Loading mod instance {} of type {}", getModId(), modClass.name)
            // Now we can load the class, so that static {} block gets called
            Class.forName(className) // todo: use modClassLoader? IMPORTANT
            // Then we check whether it's a kotlin object and return it, or if not we create a new instance of kotlin class.
            this.mod = modClass.kotlin.objectInstance ?: modClass.getConstructor().newInstance()
            logger.debug(LOADING, "Loaded mod instance {} of type {}", getModId(), modClass.name)
        } catch (e: Throwable) {
            logger.error(LOADING, "Failed to create mod instance. ModID: {}, class {}", getModId(), modClass.name, e)
            throw ModLoadingException(modInfo, event.fromStage(), "fml.modloading.failedtoloadmod", e, modClass)
        }

        logger.debug(LOADING, "Injecting Automatic event subscribers for {}", getModId())
        KotlinAutomaticEventSubscriber.inject(this, this.scanResults, this.modClass.classLoader)
        logger.debug(LOADING, "Completed Automatic event subscribers for {}", getModId())
    }

    private fun dummy(): Consumer<LifecycleEventProvider.LifecycleEvent> = Consumer {}

    private fun beforeEvent(lifecycleEvent: LifecycleEventProvider.LifecycleEvent) {}

    private fun fireEvent(lifecycleEvent: LifecycleEventProvider.LifecycleEvent) {
        val event = lifecycleEvent.getOrBuildEvent(this)
        logger.debug(LOADING, "Firing event for modid {} : {}", this.getModId(), event)
        try {
            eventBus.post(event)
            logger.debug(LOADING, "Fired event for modid {} : {}", this.getModId(), event)
        } catch (e: Throwable) {
            logger.error(LOADING, "Caught exception during event {} dispatch for modid {}", event, this.getModId(), e)
            throw ModLoadingException(modInfo, lifecycleEvent.fromStage(), "fml.modloading.errorduringevent", e)
        }

    }

    private fun afterEvent(lifecycleEvent: LifecycleEventProvider.LifecycleEvent) {
        if (currentState == ModLoadingStage.ERROR) {
            logger.error(LOADING, "An error occurred while dispatching event {} to {}", lifecycleEvent.fromStage(), getModId())
        }
    }

    private fun onEventFailed(iEventBus: IEventBus, event: Event, iEventListeners: Array<IEventListener>, i: Int, throwable: Throwable) {
        logger.error(EventBusErrorMessage(event, i, iEventListeners, throwable))
    }

    override fun matches(mod: Any): Boolean = mod === this.mod
    override fun getMod(): Any? = mod

    override fun acceptEvent(e: Event)
    {
        eventBus.post(e)
    }
}
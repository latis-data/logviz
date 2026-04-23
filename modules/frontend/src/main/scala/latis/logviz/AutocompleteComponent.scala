package latis.logviz

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import calico.html.io.{*, given}
import fs2.dom.HtmlElement
import fs2.dom.HtmlInputElement
import fs2.concurrent.SignallingRef
import fs2.concurrent.Signal

/**
 * Searchable dropdown component
 * Currently used for instance selection
 * Interacts with autocomplete store which holds states for things like dropdown and filter
*/
class AutocompleteComponent(instances: List[String], selectedInstance: SignallingRef[IO, String]) {
  def render: Resource[IO, HtmlElement[IO]] = 

    for {
      store        <- Resource.eval(AutocompleteStore())
      autocomplete <- div(
                        cls := "autocomplete",
                        div(
                          cls:= "autocomplete-input-wrapper",
                          instanceInput(store),
                        ),
                        children <-- store.isOpen.map {
                          case true =>
                            List(div( 
                              cls:= "autocomplete-panel", 
                              ul(
                                //TODO
                                //think we can add a selected if it matches selected so then we can highlight or something in css
                                cls := "instance-list",
                                
                                children <-- store.filteredInstances(instances).map { filteredList =>
                                  filteredList.map(i => 
                                    li(
                                      cls := "dropdown-item",
                                      onClick(store.select(i) >> selectedInstance.set(i)),
                                      i
                                    ))
                                }
                              )
                            ))
                          case false => Nil 
                        }
                      )

    } yield(autocomplete)

  /**
   * Input for setting the filter
   * Clicking will open a dropdown of instances that match filter
  */
  def instanceInput(store: AutocompleteStore): Resource[IO, HtmlInputElement[IO]] = 
    input.withSelf { self => 
      (
        idAttr := "instance",
        typ := "text",
        placeholder := "Select Instance",
        value <-- store.filter,

        //not sure how to implement closing dropdown when clicking outside of the dropdown
        onClick(store.openDropdown),
        onInput(self.value.get.flatMap(store.setFilter(_)))
        
      )
    }

}

/**
 * Store for updating the state of certain things such as the filter, whether to show the dropdown list of instance, etc.
 */
class AutocompleteStore(autocompleteRef: SignallingRef[IO, Autocomplete]) {

  def setFilter(f: String): IO[Unit] = autocompleteRef.update(_.copy(filter=f))

  def openDropdown: IO[Unit] = autocompleteRef.update(_.copy(dropdown=true))

  //unused right now. I imagine that when we click outside of box, we would closedropdown, but not sure how that would be done
  def closeDropdown: IO[Unit] = autocompleteRef.update(_.copy(dropdown=false))

  //TODO: selecting multiple instances?
  def select(instance: String): IO[Unit] = autocompleteRef.update(_.copy(filter=instance, dropdown=false, selected=instance))

  //exposes signal for components to listen to on whether to create dropdown or not
  def isOpen: Signal[IO, Boolean] = autocompleteRef.map(_.dropdown).changes

  def filter: Signal[IO, String] = autocompleteRef.map(_.filter).changes

  def filteredInstances(instances: List[String]): Signal[IO, List[String]] = 
    autocompleteRef.map { autocomplete =>
      val f = autocomplete.filter.toLowerCase()
      instances.filter(i => i.toLowerCase.contains(f)) 
    }.changes
}


object AutocompleteStore {
  def apply(): IO[AutocompleteStore] =
    SignallingRef[IO].of(Autocomplete("", false, "")).map(new AutocompleteStore(_))

}
case class Autocomplete(filter: String, dropdown: Boolean, selected: String)




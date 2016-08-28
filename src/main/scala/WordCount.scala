package com.pkinsky

import scala.collection.immutable
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream._
import akka._
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.Future
import play.api.libs.json.{Reads, Writes, Json}
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.model._

class WordCounter(redditAPI: RedditAPI) {
  implicit val as = ActorSystem()
  implicit val ec = as.dispatcher
  val settings = ActorMaterializerSettings(as)
  implicit val mat = ActorMaterializer(settings)

  val mapAsyncParallelism = 3

  val redditAPIRate = 500 millis

  /**
    note: can be replaced by new Source.throttle(...) builtin, retained for educational purposes
   
    builds the following stream-processing graph.
    +------------+
    | tickSource +-Unit-+
    +------------+      +---> +-----+            +-----+      +-----+
                              | zip +-(T,Unit)-> | map +--T-> | out |
    +----+              +---> +-----+            +-----+      +-----+
    | in +----T---------+
    +----+
    tickSource emits one element per `rate` time units and zip only emits when an element is present from its left and right
    input stream, so the resulting stream can never emit more than 1 element per `rate` time units.
   */
  def throttle[T](rate: FiniteDuration): Flow[T, T, NotUsed] = {
    Flow.fromGraph(GraphDSL.create(){ implicit builder =>
      import GraphDSL.Implicits._
      val zip = builder.add(Zip[T, Unit.type]())
      Source.tick(rate, rate, Unit) ~> zip.in1
      FlowShape(zip.in0, zip.out)
    }).map(_._1)
  }

  val fetchLinks: Flow[String, Link, NotUsed] =
    Flow[String]
        .via(throttle(redditAPIRate))
        .mapAsyncUnordered(mapAsyncParallelism)( subreddit => redditAPI.popularLinks(subreddit) )
        .mapConcat( listing => listing.links )


  val fetchComments: Flow[Link, Comment, NotUsed] =
    Flow[Link]
        .via(throttle(redditAPIRate))
        .mapAsyncUnordered(mapAsyncParallelism)( link => redditAPI.popularComments(link) )
        .mapConcat( listing => listing.comments )

  val wordCountSink: Sink[Comment, Future[Map[String, WordCount]]] =
    Sink.fold(Map.empty[String, WordCount])(
      (acc: Map[String, WordCount], c: Comment) => 
        mergeWordCounts(acc, Map(c.subreddit -> c.toWordCount))
    )


  val popularCommentPipeline: Flow[String, Comment, NotUsed] = 
    Flow[String]
      .via(fetchLinks)
      .via(fetchComments)


  //todo: handle popular subreddits along w/ provided list
  //todo: handle non-strict non-text msg cases?
  def websocketHandler(subreddits: immutable.Iterable[String]): Flow[Message, Message, NotUsed] = {
    val subredditSrc = if (subreddits.isEmpty)
        Source.fromFuture(redditAPI.popularSubreddits).map(WordCountRequest.apply)
      else
        Source.single(WordCountRequest(subreddits))

    Flow[Message]
      .via(JsonUtils.decode[WordCountRequest])
      .prepend(subredditSrc)
      .mapConcat(_.subreddits)
      .via(popularCommentPipeline)
      .map(c => WordCountResponse(error = None, result = Some(WordCountResult(c.subreddit, c.toWordCount))))
      .via(JsonUtils.encode[WordCountResponse])

  }

  val websocketRoute = {
    import akka.http.scaladsl.server.Directives._

    path("wordcount") {
      parameters('subreddit.*){ subreddits =>
        handleWebSocketMessages(websocketHandler(subreddits.toVector))
      }
    }
  }


  def run(subreddits: Iterable[String]): Unit = {
    // 0) Create a Flow of String names, using either
    //    the argument vector or the result of an API call.
    val subredditSrc: Source[String, NotUsed] =
      if (subreddits.isEmpty)
        Source.fromFuture(redditAPI.popularSubreddits).mapConcat(identity)
      else
        Source(subreddits.toVector)
  
    val res: Future[Map[String, WordCount]] =
      subredditSrc
      .via(popularCommentPipeline)
      .runWith(wordCountSink)

    res.onComplete(writeResults)

    as.awaitTermination()
  }
}

object Main {
  val redditAPI: RedditAPI = new RedditAPIImpl()
  val wordCount: WordCounter = new WordCounter(redditAPI)

  def main(args: Array[String]): Unit = {
    wordCount.run(args)
  }
}


object JsonUtils {
  def encode[T](implicit f: Writes[T]): Flow[T, Message, NotUsed] = 
    Flow[T].map( t => TextMessage.Strict(Json.stringify(Json.toJson(t))))

  def decode[T](implicit f: Reads[T]): Flow[Message, T, NotUsed] = 
    Flow[Message].collect{
      case TextMessage.Strict(s) => Json.parse(s).as[T] //YOLO
    }
}

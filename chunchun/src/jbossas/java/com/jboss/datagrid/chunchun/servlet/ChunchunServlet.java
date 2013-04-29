package com.jboss.datagrid.chunchun.servlet;

import com.jboss.datagrid.chunchun.jsf.InitializeCache;
import com.jboss.datagrid.chunchun.model.User;
import com.jboss.datagrid.chunchun.session.Authenticator;
import com.jboss.datagrid.chunchun.session.CacheContainerProvider;
import com.jboss.datagrid.chunchun.session.DisplayPost;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.infinispan.api.BasicCache;

import com.jboss.datagrid.chunchun.session.PostBean;
import com.jboss.datagrid.chunchun.session.UserBean;

/**
 * A servlet that invokes application logic based on URL parameters. This is to
 * avoid using layers like JSF which slows down performance and complicates
 * things when debugging.
 *
 * This servlet is dedicated mainly for performance testing.
 *
 * @author Martin Gencur
 */

@SuppressWarnings("serial")
@WebServlet(urlPatterns={"/chunchunservlet"})
public class ChunchunServlet extends HttpServlet {

   private static Map<Integer, Boolean> userMap = new HashMap<Integer, Boolean>();   //registered occupied users
   private Logger                 log                        = Logger.getLogger(this.getClass().getName());

   static {
      for (int i = 1; i != InitializeCache.getUserCount(); i++) {
         userMap.put(new Integer(i), false); //false == not currently in use == not logged in; true == logged in
      }
   }

   private static synchronized int getNextAvailableUser() {
      int index = 1;
      while (index != InitializeCache.getUserCount()) {
         if (userMap.get(index).equals(false)) {
            userMap.put(index, true);
            return index;
         }
         index++;
      }
      throw new RuntimeException("All users logged in - no available users.");
   }

   private static synchronized void markUserAsLoggedOut(int index) {
      userMap.put(index, false);
   }

   protected void doGet(HttpServletRequest request, HttpServletResponse response) throws javax.servlet.ServletException, IOException {
      response.setHeader( "Pragma", "no-cache" );
      response.setHeader( "Cache-Control", "no-cache" );
      response.setDateHeader( "Expires", 0 );

      long startTime = System.currentTimeMillis();
      String command = request.getParameter("command");
      String userParam = request.getParameter("user"); //in case we need to specify a user for an operation
      int displayLimitParam = 10; //in case we want to specify how many posts to display
      try {
         displayLimitParam = Integer.parseInt(request.getParameter("limit"));
      } catch (NumberFormatException e) {
         // do nothing, default value for displayLimitParam used
      }

      StringBuilder answer = new StringBuilder();
      Authenticator auth = getAuthenticator();
      PostBean postBean = getPostBean();
      UserBean userBean = getUserBean();

      if ("login".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=login&user=

         if (!auth.isLoggedIn()) {
            int randomUserId = ChunchunServlet.getNextAvailableUser();
            String username = "user" + randomUserId;
            String password  = "pass" + randomUserId;
            auth.setUsername(username);
            auth.setPassword(password);
            auth.login();
            answer.append("\n").append("User Logged in");
         }

      } else if ("logout".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=logout

         String indexStr = auth.getUsername().substring(4);
         int index = new Integer(indexStr);

         ChunchunServlet.markUserAsLoggedOut(index);
         auth.logoutFromServlet();
         answer.append("\n").append("User Logged out");

      } else if ("recentposts".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=recentposts     //limit defaults to 10
         //http://localhost:8080/chunchun/chunchunservlet?command=recentposts&limit=20

         postBean.setDisplayedPostsLimit(displayLimitParam);
         List<DisplayPost> recentPosts = postBean.getRecentPosts();
         answer.append("\n").append("Displayed: " + postBean.getDisplayedPostsLimit()).append("\n");
         for (DisplayPost post : recentPosts) {
            answer.append("\n").append(post.getMessage());
         }

      } else if ("newpost".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=newpost&num=1000

         int num = 1;
         try {
            num = Integer.parseInt(request.getParameter("num"));
         } catch (NumberFormatException e) {
            // do nothing, default value for num used
         }

         for (int i=1; i<=num ; i++) {
            postBean.setMessage("New message from master at " + startTime + ", number:" + i);
            postBean.sendPost();
            answer.append("\n").append("New post sent: " + postBean.getMessage());
         }
      } else if ("myposts".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=myposts

         List<DisplayPost> myPosts = postBean.getMyPosts();
         for (DisplayPost post : myPosts) {
            answer.append("\n").append(post.getMessage());
         }

      } else if ("watching".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=watching

         List<User> watchedByMe = userBean.getWatching();
         for (User user : watchedByMe) {
            answer.append("\n").append(user.getName() + " (" + user.getWhoami() + ")");
         }

      } else if ("watchers".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=watchers

         List<User> watchers = userBean.getWatchers();
         for (User user : watchers) {
            answer.append("\n").append(user.getName() + " (" + user.getWhoami() + ")");
         }

      } else if ("watchuser".equals(command)) { //watch user according to userParam parameter

         //http://localhost:8080/chunchun/chunchunservlet?command=watchuser&user=NameXY

         List<User> watchers = userBean.getWatchers();
         for (User u : watchers) {
            if (u.getName().equals(userParam)) {
               if (!userBean.isWatchedByMe(u) && !userBean.isMe(u)) {
                  userBean.watchUser(u);
                  answer.append("\n").append("Started watching user " + u.getName());
               }  else {
                  answer.append("\n").append("NoOP - I'm already watching that user");
               }
            }
         }

      } else if ("stopwatchinguser".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=stopwatchinguser&user=NameXY

         List<User> watchedByMe = userBean.getWatching();
         for (User u : watchedByMe) {
            if (u.getUsername().equals(userParam)) {
               userBean.stopWatchingUser(u);
            }
         }
      } else if ("userstats".equals(command)) {

         //http://localhost:8080/chunchun/chunchunservlet?command=userstats
         SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy kk:mm:ss");
         answer.append("Output version: 1.0.15" + " at: " + sdf. format(Calendar.getInstance().getTime()) + System.lineSeparator());

         boolean detailed = false;
         if ("detailed".equals(request.getParameter("mode"))) detailed = true; 
         
         int moreWatches = 0;
         int lessWatches = 0;
         int moreMutualWatches = 0;
         int lessMutualWatches = 0;
         int watchSelf = 0;

         for (int index = 1; index <= InitializeCache.getUserCount(); index++) {
            String curUser = "user" + index;
            User uCurUser = (User) getUserCache().get(curUser);
            List<String> watching = uCurUser.getWatching();
            if (watching.size() > InitializeCache.getUserWatchesCount()) {
               moreWatches++;
            } else if (watching.size() < InitializeCache.getUserWatchesCount()) {
               lessWatches++;
            }

            if (watching.contains(curUser)) watchSelf++;

            int mutualWatches = 0;
            for (String user: watching) {
               if (getUserCache().get(user).getWatching().contains(curUser)) {
                  mutualWatches++;
               }
            }
            final long mutualWatchesAnticipated = (long) InitializeCache.getUserMutualWatchesPercent() * InitializeCache.getUserWatchesCount();
            if (mutualWatches * 100l < mutualWatchesAnticipated) {
               lessMutualWatches++;
            } else if (mutualWatches * 100l - 1l >= mutualWatchesAnticipated) {
               moreMutualWatches++;
            }
            if (detailed) answer.append("\n").append(curUser + " posts: " + uCurUser.getPosts().size() + ", mutual watched: " + mutualWatches);
         }

         answer.append("\n").append("Total users: " + InitializeCache.getUserCount());
         answer.append("\n").append("Anticipated user watches: " + InitializeCache.getUserWatchesCount());
         answer.append("\n").append("User target mutual watches percent: " + InitializeCache.getUserMutualWatchesPercent() + "%");
         answer.append("\n").append("User target mutual watches (x100): " + (long) InitializeCache.getUserMutualWatchesPercent() * InitializeCache.getUserWatchesCount());
         answer.append("\n").append("Users with less watched users than anticipated: " + lessWatches);
         answer.append("\n").append("Users with more watched users than anticipated: " + moreWatches);
         answer.append("\n").append("Users with less mutual watched than anticipated: " + lessMutualWatches);
         answer.append("\n").append("Users with more mutual watched than anticipated: " + moreMutualWatches);
         answer.append("\n").append("Users watching themeslves: " + watchSelf);
      } else {
         answer.append("\n").append("Unknown command");
      }

      long finishedTime = System.currentTimeMillis();
      log.info("processing " + command + " took " + (finishedTime - startTime) + "msec");
//      if( answer.toString().length() != 0) {
//         response.setHeader("answer", answer.toString());
//      }
      PrintWriter out = response.getWriter();
      out.print(answer.toString());
      out.flush();
   }

   private Authenticator getAuthenticator() {
      Authenticator auth = getContextualInstance(getBeanManagerFromJNDI(), Authenticator.class);
      return auth;
   }

   private PostBean getPostBean() {
      PostBean postBean = getContextualInstance(getBeanManagerFromJNDI(), PostBean.class);
      return postBean;
   }

   private UserBean getUserBean() {
      UserBean userBean = getContextualInstance(getBeanManagerFromJNDI(), UserBean.class);
      return userBean;
   }

   private CacheContainerProvider getCacheProvider() {
      CacheContainerProvider provider = getContextualInstance(getBeanManagerFromJNDI(), CacheContainerProvider.class);
      return provider;
   }

   private BasicCache<String, User> getUserCache() {
      return getCacheProvider().getCacheContainer().getCache("userCache");
   }

   private BeanManager getBeanManagerFromJNDI() {
      InitialContext context;
      Object result;
      try {
         context = new InitialContext();
         result = context.lookup("java:comp/BeanManager");
      } catch (NamingException e) {
         throw new RuntimeException("BeanManager could not be found in JNDI", e);
      }
      return (BeanManager) result;
   }

   @SuppressWarnings("unchecked")
   public <T> T getContextualInstance(final BeanManager manager, final Class<T> type) {
      T result = null;
      Bean<T> bean = (Bean<T>) manager.resolve(manager.getBeans(type));
      if (bean != null) {
         CreationalContext<T> context = manager.createCreationalContext(bean);
         if (context != null) {
            result = (T) manager.getReference(bean, type, context);
         }
      }
      return result;
   }
}

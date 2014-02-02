package tw.skyarrow.ehreader.activity;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.ShareActionProvider;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.androidquery.AQuery;
import com.androidquery.callback.AjaxStatus;
import com.androidquery.callback.BitmapAjaxCallback;

import java.io.File;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import de.greenrobot.event.EventBus;
import tw.skyarrow.ehreader.Constant;
import tw.skyarrow.ehreader.R;
import tw.skyarrow.ehreader.db.DaoMaster;
import tw.skyarrow.ehreader.db.DaoSession;
import tw.skyarrow.ehreader.db.Photo;
import tw.skyarrow.ehreader.db.PhotoDao;
import tw.skyarrow.ehreader.event.PhotoInfoEvent;
import tw.skyarrow.ehreader.service.PhotoInfoService;
import tw.skyarrow.ehreader.util.FileInfoHelper;
import tw.skyarrow.ehreader.util.UriHelper;
import uk.co.senab.photoview.PhotoViewAttacher;

/**
 * Created by SkyArrow on 2014/1/31.
 */
public class PhotoFragment extends Fragment {
    @InjectView(R.id.page)
    TextView pageText;

    @InjectView(R.id.progress)
    ProgressBar progressBar;

    @InjectView(R.id.image)
    ImageView imageView;

    @InjectView(R.id.retry)
    Button retryBtn;

    private long galleryId;
    private int page;

    private AQuery aq;

    private SQLiteDatabase db;
    private DaoMaster daoMaster;
    private DaoSession daoSession;
    private PhotoDao photoDao;
    private EventBus bus;

    private Photo photo;
    private boolean isLoaded = false;
    private File photoFile = null;
    private String galleryTitle;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_photo, container, false);
        ButterKnife.inject(this, view);
        setHasOptionsMenu(true);

        DaoMaster.DevOpenHelper helper = new DaoMaster.DevOpenHelper(getActivity(), Constant.DB_NAME, null);
        db = helper.getWritableDatabase();
        daoMaster = new DaoMaster(db);
        daoSession = daoMaster.newSession();
        photoDao = daoSession.getPhotoDao();

        Bundle args = getArguments();
        galleryId = args.getLong("id");
        page = args.getInt("page");
        galleryTitle = args.getString("title");
        aq = new AQuery(view);

        pageText.setText(Integer.toString(page));
        requestPhotoInfo();

        return view;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bus = EventBus.getDefault();
        bus.register(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        bus.unregister(this);
        db.close();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (isLoaded) {
            inflater.inflate(R.menu.photo_fragment, menu);

            MenuItem shareItem = menu.findItem(R.id.menu_share);
            ShareActionProvider shareActionProvider = (ShareActionProvider) MenuItemCompat.getActionProvider(shareItem);
            shareActionProvider.setShareIntent(getShareIntent());

            if (photo.getBookmarked()) {
                menu.findItem(R.id.menu_add_bookmark).setVisible(false);
                menu.findItem(R.id.menu_remove_bookmark).setVisible(true);
            } else {
                menu.findItem(R.id.menu_add_bookmark).setVisible(true);
                menu.findItem(R.id.menu_remove_bookmark).setVisible(false);
            }
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_add_bookmark:
                bookmarkPhoto(true);
                return true;

            case R.id.menu_remove_bookmark:
                bookmarkPhoto(false);
                return true;

            case R.id.menu_open_in_browser:
                openInBrowser();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private Intent getShareIntent() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        String filename = photo.getFilename();

        if (photoFile == null) {
            photoFile = aq.makeSharedFile(photo.getSrc(), filename);
        }

        intent.setType(FileInfoHelper.getMimeType(filename));
        intent.putExtra(Intent.EXTRA_TEXT, galleryTitle + " " + UriHelper.getPhotoUri(photo));
        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(photoFile));

        return intent;
    }

    public void onEventMainThread(PhotoInfoEvent event) {
        Photo photo = event.getPhoto();

        if (photo == null) return;

        if (photo.getGalleryId() == galleryId && photo.getPage() == page) {
            this.photo = photo;
            getActivity().supportInvalidateOptionsMenu();
            loadImage();
        }
    }

    private void bookmarkPhoto(boolean isBookmarked) {
        photo.setBookmarked(isBookmarked);
        photoDao.update(photo);

        if (isBookmarked) {
            Toast.makeText(getActivity(), R.string.notification_bookmark_added, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getActivity(), R.string.notification_bookmark_removed, Toast.LENGTH_SHORT).show();
        }

        getActivity().supportInvalidateOptionsMenu();
    }

    private void requestPhotoInfo() {
        Intent intent = new Intent(getActivity(), PhotoInfoService.class);

        intent.putExtra(PhotoInfoService.GALLERY_ID, galleryId);
        intent.putExtra(PhotoInfoService.PHOTO_PAGE, page);

        getActivity().startService(intent);
    }

    @OnClick(R.id.retry)
    void onRetryClick() {
        retryBtn.setVisibility(View.GONE);

        requestPhotoInfo();
    }

    private void loadImage() {
        if (photo.getDownloaded()) {
            photoFile = UriHelper.getPhotoFile(photo);
            if (!photoFile.exists()) photoFile = null;
        }

        if (photoFile == null) {
            loadImageCallback.url(photo.getSrc());
        } else {
            loadImageCallback.file(photoFile);
        }

        progressBar.setIndeterminate(false);
        loadImageCallback.progress(progressBar);
        aq.id(R.id.image).image(loadImageCallback);
    }

    private BitmapAjaxCallback loadImageCallback = new BitmapAjaxCallback() {
        @Override
        protected void callback(String url, ImageView iv, Bitmap bm, AjaxStatus status) {
            if (bm == null && status.getCode() != 200) {
                photo.setInvalid(true);
                photoDao.update(photo);
                retryBtn.setVisibility(View.VISIBLE);
                progressBar.setVisibility(View.GONE);
            } else if (iv != null) {
                pageText.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);
                iv.setImageBitmap(bm);
                iv.setVisibility(View.VISIBLE);
                new PhotoViewAttacher(iv);

                isLoaded = true;
                getActivity().supportInvalidateOptionsMenu();
            }
        }
    };

    private void openInBrowser() {
        Intent intent = new Intent(Intent.ACTION_VIEW);

        intent.setData(UriHelper.getPhotoUri(photo));
        startActivity(intent);
    }
}
